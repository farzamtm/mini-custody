package com.farzam.custody.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.JournalTransactionRepository;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.support.AbstractKafkaTest;
import com.farzam.custody.support.TestSigner;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventSignature;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalApproved;
import com.farzam.events.WithdrawalBroadcast;
import com.farzam.events.WithdrawalSigningFailed;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * M4 acceptance test: a withdrawal goes out over Kafka, and what the signer says comes back in.
 *
 * <p>Nothing here is switched off. The relay's timer runs, the listener container runs, and the
 * broker is the same image {@code docker-compose.yml} starts. The assertions wait for those threads
 * rather than driving them, because the thing being tested is that the application moves an event
 * from a database row to a state change on its own.
 *
 * <p>{@code @ActiveProfiles("dev")} for two endpoints that only exist under it: deposits, which is
 * how a balance comes into being, and the approval stand-in, which is used here rather than the real
 * approval endpoint because nothing in this file is about who signed. What an approved withdrawal
 * with genuine signatures on it looks like is {@code ApprovalEventFlowIntegrationTest}.
 *
 * <p>Every test uses a fresh client and a fresh withdrawal, so tests can share a broker and a
 * database without sharing state. Assertions about {@code PENDING_OUT}, which is a singleton, are
 * written as deltas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WithdrawalEventFlowIntegrationTest extends AbstractKafkaTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    /**
     * Obviously not a real hash, on purpose.
     *
     * <p>A transaction hash and an Ethereum private key are both 64 hex characters, so no scanner
     * can tell them apart by shape — the secret scan rejected an earlier, realistic-looking literal
     * here and was right to, because the alternative is a rule that never fires on a real key. Test
     * fixtures in this repository therefore use hashes no key could be mistaken for. See
     * {@code .gitleaks.toml}.
     */
    private static final String TX_HASH = "0x" + "deadbeef".repeat(8);

    /** Generous: the relay ticks every 500 ms and a first Kafka connection is not instant. */
    private static final Duration LISTEN = Duration.ofSeconds(6);

    private static final Duration SETTLES_WITHIN = Duration.ofSeconds(20);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JournalTransactionRepository transactions;

    private UUID clientId;
    private UUID accountId;
    private Withdrawal withdrawal;

    @BeforeEach
    void requestAWithdrawalOfPointFourEth() {
        clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, ONE_ETH).getId();
        withdrawal = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
    }

    // ---- Out: approval to the signer ---------------------------------------

    @Test
    void approvingAWithdrawalPublishesExactlyOneApprovedEvent() {
        assertThat(approve()).hasStatus(200);

        List<ConsumerRecord<String, String>> published = drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN);

        assertThat(published).as("exactly one, published by the relay without anyone asking").hasSize(1);
        EventEnvelope envelope = EventJson.read(published.getFirst().value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo(EventType.WITHDRAWAL_APPROVED);
        assertThat(envelope.payloadAs(WithdrawalApproved.class).amountWei()).isEqualTo(POINT_FOUR_ETH);
        assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.APPROVED);
    }

    @Test
    void approvingTwiceIsRefusedAndDoesNotPublishASecondEvent() {
        assertThat(approve()).hasStatus(200);

        MvcTestResult again = approve();

        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("ILLEGAL_STATE_TRANSITION");
        // The second call never reached the outbox, because the state machine refused before it got
        // there and the whole thing rolled back.
        assertThat(drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN)).hasSize(1);
    }

    @Test
    void approvingSomethingThatDoesNotExistIsANotFound() {
        assertThat(mvc.post().uri("/dev/withdrawals/{id}/approve", UUID.randomUUID()).exchange()).hasStatus(404);
    }

    // ---- In: results from the signer ---------------------------------------

    @Test
    void aBroadcastResultRecordsTheHashAndLeavesTheFundsHeld() {
        approve();
        BigInteger heldBefore = ledger.balanceOf(SystemAccounts.PENDING_OUT);

        publishResult(
                UUID.randomUUID(),
                EventType.WITHDRAWAL_BROADCAST,
                new WithdrawalBroadcast(withdrawal.getId(), TX_HASH));

        await().atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.BROADCAST));
        assertThat(reload().getTxHash()).isEqualTo(TX_HASH);
        // Broadcast is not settled. The transaction can still be dropped or reverted, so the money
        // stays in PENDING_OUT until M6 counts three confirmations.
        assertThat(ledger.balanceOf(SystemAccounts.PENDING_OUT)).isEqualTo(heldBefore);
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
    }

    @Test
    void aRefusalFailsTheWithdrawalAndGivesTheMoneyBack() {
        approve();

        publishResult(
                UUID.randomUUID(),
                EventType.WITHDRAWAL_SIGNING_FAILED,
                new WithdrawalSigningFailed(withdrawal.getId(), "quorum not met"));

        await().atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.FAILED));
        assertThat(reload().getFailureReason()).isEqualTo("quorum not met");
        assertThat(ledger.balanceOf(accountId)).as("the hold came back").isEqualTo(ONE_ETH);
    }

    @Test
    void theSameResultDeliveredTwiceChangesStateOnce() {
        approve();
        UUID eventId = UUID.randomUUID();
        var refusal = new WithdrawalSigningFailed(withdrawal.getId(), "quorum not met");

        // The same event id twice: what a relay resend, a rebalance or a crash before the offset
        // commit all look like from in here.
        publishResult(eventId, EventType.WITHDRAWAL_SIGNING_FAILED, refusal);
        publishResult(eventId, EventType.WITHDRAWAL_SIGNING_FAILED, refusal);

        await().atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.FAILED));

        // The assertion that matters is not the status — a second FAILED would have thrown on the
        // state machine anyway — but the money. Releasing the hold twice would hand the client
        // 0.4 ETH they never had, and the ledger would still balance.
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH);
        assertThat(transactions.countByKindAndReferenceId(JournalKind.WITHDRAWAL_RELEASE, withdrawal.getId()))
                .as("the hold was released once")
                .isEqualTo(1);
    }

    /**
     * The M7 criterion, and the reason any of this exists.
     *
     * <p>Before the signature check, this exact message released the hold: a hand-built refusal with
     * a fresh event id, published straight to the topic by something that is not the signer. The
     * withdrawal is in APPROVED, so the signer is at this moment signing and broadcasting it — and
     * crediting the client back while that happens is a double-spend, with the ETH gone and the books
     * saying it never left.
     *
     * <p>The assertion that matters is the balance. The status is asserted too, but a test that only
     * checked the status would have passed against a version that released the money and then failed
     * the state transition.
     */
    @Test
    void anUnsignedRefusalIsRefusedAndTheMoneyStaysHeld() {
        approve();
        BigInteger heldBefore = ledger.balanceOf(SystemAccounts.PENDING_OUT);

        kafka.send(
                Topics.SIGNER_RESULTS,
                withdrawal.getId().toString(),
                result(
                        UUID.randomUUID(),
                        EventType.WITHDRAWAL_SIGNING_FAILED,
                        new WithdrawalSigningFailed(withdrawal.getId(), "give it back")));

        List<ConsumerRecord<String, String>> deadLettered = drain(
                Topics.dlt(Topics.SIGNER_RESULTS),
                withdrawal.getId(),
                LISTEN);

        assertThat(deadLettered).as("set aside rather than applied").hasSize(1);
        assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(ledger.balanceOf(accountId)).as("no money came back").isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
        assertThat(ledger.balanceOf(SystemAccounts.PENDING_OUT)).as("the hold stands").isEqualTo(heldBefore);
        assertThat(transactions.countByKindAndReferenceId(JournalKind.WITHDRAWAL_RELEASE, withdrawal.getId()))
                .as("nothing was released")
                .isZero();
    }

    /**
     * A signature that is real, over these exact bytes, by a key custody-api was not deployed with.
     *
     * <p>The distinction from the unsigned case is worth its own test: it is the difference between
     * "did anybody sign this" and "did the right party sign this", and an implementation that
     * verified the header was well-formed without checking whose key it was would pass the first and
     * fail here.
     */
    @Test
    void anImpostorsResultIsRefused() {
        approve();

        kafka.send(
                signedBy(
                        TestSigner.generate(),
                        Topics.SIGNER_RESULTS,
                        withdrawal.getId().toString(),
                        result(
                                UUID.randomUUID(),
                                EventType.WITHDRAWAL_SIGNING_FAILED,
                                new WithdrawalSigningFailed(withdrawal.getId(), "give it back"))));

        assertThat(drain(Topics.dlt(Topics.SIGNER_RESULTS), withdrawal.getId(), LISTEN)).hasSize(1);
        assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
    }

    /**
     * The signature covers the message, so a genuine one cannot be lifted onto a different result.
     *
     * <p>This is the attack left open by a scheme that signed only, say, the withdrawal id: take the
     * signature from the broadcast result the signer really did publish, and reuse it on a refusal.
     */
    @Test
    void aGenuineSignatureCannotBeReplayedOntoADifferentResult() {
        approve();
        String broadcast = result(
                UUID.randomUUID(),
                EventType.WITHDRAWAL_BROADCAST,
                new WithdrawalBroadcast(withdrawal.getId(), TX_HASH));
        String refusal = result(
                UUID.randomUUID(),
                EventType.WITHDRAWAL_SIGNING_FAILED,
                new WithdrawalSigningFailed(withdrawal.getId(), "give it back"));

        // The header from the broadcast, the body of the refusal.
        var forged = new ProducerRecord<>(Topics.SIGNER_RESULTS, withdrawal.getId().toString(), refusal);
        forged.headers().add(EventSignature.HEADER, SIGNER.sign(broadcast).getBytes(StandardCharsets.UTF_8));
        kafka.send(forged);

        assertThat(drain(Topics.dlt(Topics.SIGNER_RESULTS), withdrawal.getId(), LISTEN)).hasSize(1);
        assertThat(statusOf(withdrawal.getId())).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
    }

    @Test
    void aMalformedMessageEndsUpOnTheDeadLetterTopic() {
        UUID key = UUID.randomUUID();

        // Signed, so that what this test exercises is still the parse failure rather than the
        // signature check that now runs before it.
        kafka.send(signed(Topics.SIGNER_RESULTS, key.toString(), "{ this is not an event"));

        List<ConsumerRecord<String, String>> deadLettered = drain(Topics.dlt(Topics.SIGNER_RESULTS), key, LISTEN);

        assertThat(deadLettered).as("set aside rather than retried forever").hasSize(1);
        assertThat(deadLettered.getFirst().value()).isEqualTo("{ this is not an event");
        // Straight there, with no retries: EventFormatException is registered as non-retryable
        // because invalid JSON will be exactly as invalid in two seconds' time. A partition blocked
        // behind a message that can never succeed is the failure this avoids.
    }

    // ---- Fixtures ----------------------------------------------------------

    private MvcTestResult approve() {
        return mvc.post().uri("/dev/withdrawals/{id}/approve", withdrawal.getId()).exchange();
    }

    private void publishResult(UUID eventId, EventType type, Object payload) {
        kafka.send(signed(Topics.SIGNER_RESULTS, withdrawal.getId().toString(), result(eventId, type, payload)));
    }

    private String result(UUID eventId, EventType type, Object payload) {
        return EventJson.write(EventEnvelope.of(eventId, type, Instant.now(), withdrawal.getId(), payload));
    }

    private WithdrawalStatus statusOf(UUID id) {
        return withdrawalRepository.findById(id).orElseThrow().getStatus();
    }

    private Withdrawal reload() {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
    }
}
