package com.farzam.signer.support;

import com.farzam.crypto.Ed25519;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import com.farzam.events.WithdrawalApproved;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.EdECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * Postgres, Kafka and Anvil, plus the keys a signer needs to exist at all.
 *
 * <p>All three containers are started once in a static initialiser and left to Testcontainers' Ryuk
 * sidecar, which removes them when the JVM exits. One set for the whole module: a node and a broker
 * per test class would dominate the run.
 *
 * <p><b>Every key here is generated per run, and that is a requirement rather than a preference.</b>
 * Anvil prints ten well-known development private keys on startup and it would be easy to paste one
 * in. The secret scanner would then be looking at sixty-four hex characters of real entropy in a
 * committed file and would have to be told to ignore it — and a rule with an exception is a rule
 * that grows exceptions. Generating the wallet and funding it with {@code anvil_setBalance} means
 * nothing that looks like a key is ever written down.
 */
public abstract class AbstractSignerTest {

    /** 100 ETH, so no test has to think about the gas its transfers cost. */
    private static final BigInteger HOT_WALLET_FUNDING = BigInteger.TEN.pow(20);

    private static final int MASTER_KEY_BYTES = 32;

    @SuppressWarnings("resource") // stopped by Ryuk at JVM exit, by design
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @SuppressWarnings("resource")
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @SuppressWarnings("resource")
    private static final AnvilContainer ANVIL = new AnvilContainer();

    /** Two approvers, so a test can tell "two signatures" from "one signature twice". */
    protected static final TestApprover APPROVER_ONE = TestApprover.generate();

    protected static final TestApprover APPROVER_TWO = TestApprover.generate();

    /** Trusted by nobody. Its signatures are valid arithmetic and worth nothing. */
    protected static final TestApprover OUTSIDER = TestApprover.generate();

    protected static final String HOT_WALLET_ADDRESS;

    /** Hex, no prefix. The value the "nothing leaks into the logs" test looks for. */
    protected static final String HOT_WALLET_PRIVATE_KEY;

    /** Base64. The other value that test looks for. */
    protected static final String MASTER_KEY;

    /** Base64 seed, for {@code signer.results.signing-key}. Generated per run like everything else. */
    protected static final String RESULTS_SIGNING_KEY;

    /** The matching public key, for a test that verifies what the relay published. */
    protected static final PublicKey RESULTS_PUBLIC_KEY;

    static {
        POSTGRES.start();
        KAFKA.start();
        ANVIL.start();

        ECKeyPair wallet = generateWallet();
        HOT_WALLET_ADDRESS = "0x" + Keys.getAddress(wallet);
        HOT_WALLET_PRIVATE_KEY = Numeric.toHexStringNoPrefixZeroPadded(wallet.getPrivateKey(), 64);

        byte[] masterKey = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(masterKey);
        MASTER_KEY = Base64.getEncoder().encodeToString(masterKey);

        KeyPair results = generateResultsKey();
        RESULTS_SIGNING_KEY = Base64.getEncoder()
                .encodeToString(((EdECPrivateKey) results.getPrivate()).getBytes().orElseThrow());
        RESULTS_PUBLIC_KEY = results.getPublic();

        new TestRpc(ANVIL.rpcUrl()).setBalance(HOT_WALLET_ADDRESS, HOT_WALLET_FUNDING);
    }

    /**
     * Points the application at the three containers and gives it its keys and its policy.
     *
     * @param registry Spring's test property registry
     */
    @DynamicPropertySource
    static void signerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("chain.rpc-url", ANVIL::rpcUrl);
        // Anvil's default, and the value docker-compose.yml runs with.
        registry.add("chain.chain-id", () -> 31337);

        registry.add("signer.master-key", () -> MASTER_KEY);
        registry.add("signer.hot-wallet.address", () -> HOT_WALLET_ADDRESS);
        registry.add("signer.hot-wallet.import-private-key", () -> HOT_WALLET_PRIVATE_KEY);

        registry.add("signer.policy.max-transaction-wei", () -> "5000000000000000000");
        registry.add("signer.policy.second-approval-from-wei", () -> "1000000000000000000");
        registry.add("signer.policy.trusted-approvers[0].id", APPROVER_ONE::id);
        registry.add("signer.policy.trusted-approvers[0].public-key", APPROVER_ONE::publicKeyBase64);
        registry.add("signer.policy.trusted-approvers[1].id", APPROVER_TWO::id);
        registry.add("signer.policy.trusted-approvers[1].public-key", APPROVER_TWO::publicKeyBase64);

        // Without this the context does not start at all: an absent results key is a startup
        // failure rather than a fail-closed default, because a signer that signs transactions and
        // cannot authenticate its own reports strands every withdrawal it handles.
        registry.add("signer.results.signing-key", () -> RESULTS_SIGNING_KEY);

        // The retry job is driven by the tests that care about it, not by a timer. A test that has to
        // sleep to find out what happened fails on a loaded CI runner for no reason.
        registry.add("signer.broadcast.scheduled", () -> false);
    }

    /**
     * @return a client for the chain the signer is pointed at
     */
    protected static TestRpc chain() {
        return new TestRpc(ANVIL.rpcUrl());
    }

    /**
     * An address nothing has ever paid.
     *
     * <p>Derived from a throwaway key pair rather than from random bytes, so it is an address that
     * could really exist. A test that pays to twenty random bytes would pass just as well and would
     * not have checked that the signer's idea of an address matches the chain's.
     *
     * @return a fresh destination, lower-case hex
     */
    protected static String freshAddress() {
        return "0x" + Keys.getAddress(generateWallet());
    }

    /**
     * Builds an approval event exactly as the outbox relay would publish it.
     *
     * @param withdrawalId which withdrawal, and the message key
     * @param destination where the funds go
     * @param amountWei how much
     * @param approvals the evidence, genuine or otherwise
     * @return the JSON to put on the topic
     */
    protected static String approvalEvent(
            UUID withdrawalId,
            String destination,
            BigInteger amountWei,
            List<WithdrawalApproved.Approval> approvals) {
        return approvalEvent(UUID.randomUUID(), withdrawalId, destination, amountWei, approvals);
    }

    /**
     * The same, with the event id chosen by the caller.
     *
     * <p>Which is what a redelivery is: the identical event id arriving twice. A test that wants to
     * prove the duplicate check works has to be able to say so.
     *
     * @param eventId the envelope's event id
     * @param withdrawalId which withdrawal
     * @param destination where the funds go
     * @param amountWei how much
     * @param approvals the evidence
     * @return the JSON to put on the topic
     */
    protected static String approvalEvent(
            UUID eventId,
            UUID withdrawalId,
            String destination,
            BigInteger amountWei,
            List<WithdrawalApproved.Approval> approvals) {
        return EventJson.write(
                EventEnvelope.of(
                        eventId,
                        EventType.WITHDRAWAL_APPROVED,
                        Instant.now(),
                        withdrawalId,
                        new WithdrawalApproved(withdrawalId, destination, amountWei, approvals)));
    }

    /**
     * Publishes one message, keyed the way the relay keys it.
     *
     * @param topic where to put it
     * @param key the withdrawal id
     * @param value the envelope JSON
     */
    protected static void publish(String topic, UUID key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.ACKS_CONFIG,
                        "all"))) {
            producer.send(new ProducerRecord<>(topic, key.toString(), value));
            producer.flush();
        }
    }

    /**
     * Collects messages on a topic that carry one key, for a fixed window.
     *
     * <p>Filtering by key is what lets these tests share a broker: the key is a withdrawal id and
     * every test makes its own, so no test can see another's messages. The window is drained in full
     * rather than returning at the first match, because most of the assertions are about a count —
     * "exactly one" cannot be told from "three" by a helper that stops at the first.
     *
     * @param topic which topic
     * @param key the withdrawal id to keep
     * @param window how long to listen
     * @return the matching messages, in arrival order
     */
    protected static List<ConsumerRecord<String, String>> drain(String topic, UUID key, Duration window) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "test-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));

            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : polled) {
                    if (key.toString().equals(record.key())) {
                        received.add(record);
                    }
                }
            }
        }
        return received;
    }

    private static ECKeyPair generateWallet() {
        try {
            return Keys.createEcKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("could not generate a secp256k1 key pair", impossible);
        }
    }

    /** Ed25519, and nothing to do with the wallet: this one authenticates results, it cannot spend. */
    private static KeyPair generateResultsKey() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("this JDK has no Ed25519", impossible);
        }
    }
}
