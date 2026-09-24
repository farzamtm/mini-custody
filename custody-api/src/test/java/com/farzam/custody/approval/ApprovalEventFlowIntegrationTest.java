package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.crypto.Ed25519;
import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractKafkaTest;
import com.farzam.custody.support.TestApprover;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import com.farzam.events.ApprovalStatement;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalApproved;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M3 acceptance test: two approvals become one event the signer can actually verify.
 *
 * <p>Everything runs. The relay's timer ticks, the broker is the image {@code docker-compose.yml}
 * starts, and the assertions wait for those threads rather than driving them.
 *
 * <p><b>The last assertion is the one that matters, and it is worth saying why.</b> It re-verifies
 * each published approval exactly as {@code SigningPolicy} does in the other service: it rebuilds
 * the {@link ApprovalStatement} from the event's own fields and checks the signature against the key
 * the approval carries, using {@link Ed25519} from {@code common}. That is not a re-run of what this
 * service already did — this service signed a statement built from a database row, and what is
 * checked here is a statement built from JSON that has been through a {@code jsonb} column, a
 * canonical serialisation and a Kafka topic. If any of those changed a byte, custody-api would be
 * collecting approvals the signer would reject, and the withdrawal would stall with nobody able to
 * say why. The two services cannot be run in one test; agreeing on the bytes is the next best thing,
 * and it is the thing that would break.
 */
@SpringBootTest
class ApprovalEventFlowIntegrationTest extends AbstractKafkaTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger TWO_ETH = new BigInteger("2000000000000000000");
    private static final BigInteger HALF_ETH = new BigInteger("500000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** Generous: the relay ticks every 500 ms and a first Kafka connection is not instant. */
    private static final Duration LISTEN = Duration.ofSeconds(6);

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApproverService registry;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    private UUID clientId;
    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    @Test
    void completingTheQuorumPublishesOneEventCarryingBothApprovals() {
        Withdrawal withdrawal = request(TWO_ETH);
        Signatory alice = staff();
        Signatory bob = staff();

        approve(withdrawal, alice);
        approve(withdrawal, bob);

        List<ConsumerRecord<String, String>> published = drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN);
        assertThat(published).as("one event, published when the quorum completed and not before").hasSize(1);

        EventEnvelope envelope = EventJson.read(published.getFirst().value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo(EventType.WITHDRAWAL_APPROVED);

        WithdrawalApproved event = envelope.payloadAs(WithdrawalApproved.class);
        assertThat(event.amountWei()).isEqualTo(TWO_ETH);
        assertThat(event.destination()).isEqualTo(DESTINATION);
        assertThat(event.approvals()).extracting(WithdrawalApproved.Approval::approverId)
                .containsExactlyInAnyOrder(alice.id(), bob.id());

        everyApprovalVerifiesTheWayTheSignerWillCheckIt(event);
    }

    /**
     * The first approval on a two-approver withdrawal publishes nothing.
     *
     * <p>Worth its own test rather than being implied by the one above. An event written when the
     * first approval landed would reach the signer, be refused for a short quorum, and release the
     * client's hold — turning a withdrawal that was one signature away from going out into a failed
     * one.
     */
    @Test
    void anIncompleteQuorumPublishesNothing() {
        Withdrawal withdrawal = request(TWO_ETH);

        approve(withdrawal, staff());

        assertThat(drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN)).isEmpty();
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    /** Below the threshold the single approval is the quorum, and the event goes out at once. */
    @Test
    void aSmallWithdrawalGoesOutOnOneApproval() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();

        approve(withdrawal, alice);

        List<ConsumerRecord<String, String>> published = drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN);
        assertThat(published).hasSize(1);

        WithdrawalApproved event = EventJson.read(published.getFirst().value(), EventEnvelope.class)
                .payloadAs(WithdrawalApproved.class);
        assertThat(event.approvals()).hasSize(1);
        everyApprovalVerifiesTheWayTheSignerWillCheckIt(event);
    }

    /**
     * {@code SigningPolicy.countValidApprovals}, as nearly as it can be reproduced here.
     *
     * <p>The one difference is which key is used, and it is the difference the whole architecture
     * turns on: the signer looks the approver up in its own configuration and verifies against
     * <em>that</em> key, never the one on the event. Here there is no such configuration, so the
     * event's own key is used — which proves the bytes agree, and proves nothing about trust. That
     * second half is the signer's to prove, and {@code SigningPolicyTest} does.
     */
    private static void everyApprovalVerifiesTheWayTheSignerWillCheckIt(WithdrawalApproved event) {
        byte[] statement = ApprovalStatement.of(event).canonicalBytes();

        for (WithdrawalApproved.Approval approval : event.approvals()) {
            assertThat(
                    Ed25519.verify(
                            Ed25519.publicKeyFrom(Base64.getDecoder().decode(approval.publicKey())),
                            statement,
                            Base64.getDecoder().decode(approval.signature())))
                    .as("the approval from %s survives the round trip through jsonb and Kafka", approval.approverId())
                    .isTrue();
        }
    }

    // ---- Fixtures -----------------------------------------------------------

    private record Signatory(UUID id, TestApprover keys) {}

    private Withdrawal request(BigInteger amount) {
        return withdrawals.request(new WithdrawalCommand(accountId, DESTINATION, amount, UUID.randomUUID().toString()));
    }

    private Signatory staff() {
        TestApprover keys = TestApprover.generate();
        return new Signatory(registry.register("staff", keys.publicKeyBase64(), null).getId(), keys);
    }

    private void approve(Withdrawal withdrawal, Signatory signatory) {
        approvals.submit(
                withdrawal.getId(),
                signatory.id(),
                signatory.keys().sign(withdrawal.getId(), withdrawal.getDestination(), withdrawal.getAmount()));
    }

    private WithdrawalStatus statusOf(Withdrawal withdrawal) {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus();
    }
}
