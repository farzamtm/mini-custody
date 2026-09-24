package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.TestApprover;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.IllegalStateTransitionException;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import com.farzam.events.ApprovalStatement;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Every way an approval is accepted or refused, against a real Postgres.
 *
 * <p>The refusals are the point. Each is a way somebody would try to get a withdrawal approved that
 * should not be, and they are cheap enough to enumerate exhaustively — unlike the end-to-end flow,
 * which is one test in {@link ApprovalEventFlowIntegrationTest}.
 *
 * <p>{@link WithoutKafka}: nothing here needs a broker. The cases that complete a quorum do write an
 * outbox row, and that the row becomes a message is M4's test rather than this one's.
 */
@SpringBootTest
@WithoutKafka
class ApprovalServiceIntegrationTest extends AbstractPostgresTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger TWO_ETH = new BigInteger("2000000000000000000");
    private static final BigInteger HALF_ETH = new BigInteger("500000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApproverService registry;

    @Autowired
    private ApprovalRepository approvalRepository;

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

    // ---- The quorum ---------------------------------------------------------

    @Test
    void belowTheThresholdOneApprovalIsEnoughAndApprovesTheWithdrawal() {
        Withdrawal withdrawal = request(HALF_ETH);

        ApprovalService.ApprovalOutcome outcome = approve(withdrawal, staff());

        assertThat(outcome.collected()).isEqualTo(1);
        assertThat(outcome.required()).isEqualTo(1);
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.APPROVED);
    }

    @Test
    void atTheThresholdOneApprovalLeavesTheWithdrawalWaiting() {
        Withdrawal withdrawal = request(TWO_ETH);

        ApprovalService.ApprovalOutcome outcome = approve(withdrawal, staff());

        assertThat(outcome.collected()).isEqualTo(1);
        assertThat(outcome.required()).isEqualTo(2);
        assertThat(statusOf(withdrawal)).as("still waiting for a second pair of eyes")
                .isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    @Test
    void theSecondApproverCompletesTheQuorum() {
        Withdrawal withdrawal = request(TWO_ETH);
        Signatory alice = staff();
        Signatory bob = staff();

        approve(withdrawal, alice);
        ApprovalService.ApprovalOutcome outcome = approve(withdrawal, bob);

        assertThat(outcome.collected()).isEqualTo(2);
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(approvalRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawal.getId()))
                .as("both signatures are kept as evidence, in the order they were given")
                .extracting(Approval::getApproverId)
                .containsExactly(alice.id(), bob.id());
    }

    /**
     * The whole of four eyes, in one test.
     *
     * <p>One approver with a valid signature, submitted twice, must not satisfy a two-approver
     * quorum. It is a copy and a paste away from happening, and a quorum that counted approvals
     * rather than approvers would let it through while looking entirely correct in the log.
     */
    @Test
    void oneApproverCannotSatisfyATwoApproverQuorumAlone() {
        Withdrawal withdrawal = request(TWO_ETH);
        Signatory alice = staff();
        String signature = alice.keys().sign(statement(withdrawal));

        approvals.submit(withdrawal.getId(), alice.id(), signature);

        assertThatExceptionOfType(AlreadyApprovedException.class)
                .isThrownBy(() -> approvals.submit(withdrawal.getId(), alice.id(), signature));
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
        assertThat(approvalRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawal.getId())).hasSize(1);
    }

    // ---- Signatures ---------------------------------------------------------

    /**
     * The property the whole scheme rests on: the statement is rebuilt from the withdrawal, so a
     * signature over any other terms is not a signature over this payment.
     */
    @Test
    void aSignatureOverADifferentDestinationDoesNotVerify() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();
        var elsewhere = new ApprovalStatement(withdrawal.getId(), "0x" + "a".repeat(40), HALF_ETH);

        assertThatExceptionOfType(InvalidApprovalSignatureException.class)
                .isThrownBy(() -> approvals.submit(withdrawal.getId(), alice.id(), alice.keys().sign(elsewhere)));
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    @Test
    void aSignatureOverADifferentAmountDoesNotVerify() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();
        var cheaper = new ApprovalStatement(withdrawal.getId(), DESTINATION, BigInteger.ONE);

        assertThatExceptionOfType(InvalidApprovalSignatureException.class)
                .isThrownBy(() -> approvals.submit(withdrawal.getId(), alice.id(), alice.keys().sign(cheaper)));
    }

    @Test
    void aSignatureOverAnotherWithdrawalDoesNotVerify() {
        Withdrawal withdrawal = request(HALF_ETH);
        Withdrawal other = request(HALF_ETH);
        Signatory alice = staff();

        assertThatExceptionOfType(InvalidApprovalSignatureException.class).isThrownBy(
                () -> approvals.submit(withdrawal.getId(), alice.id(), alice.keys().sign(statement(other))));
    }

    /**
     * Somebody else's key, under this approver's id.
     *
     * <p>The signature is genuine — it is simply not from the person the approval claims to be from.
     * The registry decides which key a signature is checked against, and nothing in the request does.
     */
    @Test
    void aSignatureFromAKeyThisApproverDoesNotHoldDoesNotVerify() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();
        TestApprover impostor = TestApprover.generate();

        assertThatExceptionOfType(InvalidApprovalSignatureException.class).isThrownBy(
                () -> approvals.submit(withdrawal.getId(), alice.id(), impostor.sign(statement(withdrawal))));
    }

    @Test
    void aGarbledSignatureDoesNotVerify() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();

        assertThatExceptionOfType(InvalidApprovalSignatureException.class).isThrownBy(
                () -> approvals
                        .submit(withdrawal.getId(), alice.id(), alice.keys().garbledSignature(statement(withdrawal))));
    }

    /**
     * Nonsense where a signature should be does not reach the verifier as an exception.
     *
     * <p>The contract's pattern rejects this at the edge, so it can only arrive from a caller inside
     * the process — but a service that is correct only while the schema says what it says today is
     * not correct. It fails as an invalid signature, which is what it is.
     */
    @Test
    void somethingThatIsNotBase64IsAnInvalidSignatureRatherThanACrash() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();

        assertThatExceptionOfType(InvalidApprovalSignatureException.class)
                .isThrownBy(() -> approvals.submit(withdrawal.getId(), alice.id(), "not base64 at all!!"));
    }

    // ---- Who may approve ----------------------------------------------------

    @Test
    void anUnregisteredApproverIsRefused() {
        Withdrawal withdrawal = request(HALF_ETH);

        assertThatExceptionOfType(UnknownApproverException.class)
                .isThrownBy(() -> approvals.submit(withdrawal.getId(), UUID.randomUUID(), unsignedBytes()));
    }

    /**
     * Four eyes means two independent people, not two key pairs.
     *
     * <p>The signature here is perfectly valid. What is wrong is who gave it: this approver acts for
     * the client whose money is moving, so counting them would leave the control satisfied by one
     * party holding both keys.
     */
    @Test
    void anApproverWhoActsForTheClientCannotApproveTheirWithdrawal() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory theirOwn = register("the client's own", clientId);

        assertThatExceptionOfType(SelfApprovalException.class).isThrownBy(() -> approve(withdrawal, theirOwn));
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    /** An approver who acts for a different client is independent of this one, and counts. */
    @Test
    void anApproverWhoActsForAnotherClientIsIndependentAndCounts() {
        Withdrawal withdrawal = request(HALF_ETH);

        approve(withdrawal, register("another client's", UUID.randomUUID()));

        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.APPROVED);
    }

    // ---- State --------------------------------------------------------------

    @Test
    void approvingAWithdrawalThatHasAlreadyBeenApprovedIsRefused() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory bob = staff();
        approve(withdrawal, staff());

        assertThatExceptionOfType(IllegalStateTransitionException.class).isThrownBy(() -> approve(withdrawal, bob));
    }

    @Test
    void approvingAWithdrawalThatDoesNotExistIsEmptyRatherThanAnError() {
        Optional<ApprovalService.ApprovalOutcome> outcome = approvals
                .submit(UUID.randomUUID(), staff().id(), unsignedBytes());

        assertThat(outcome).isEmpty();
    }

    // ---- Registration -------------------------------------------------------

    @Test
    void aPublicKeyOfTheWrongLengthIsRejectedAtRegistration() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatExceptionOfType(MalformedPublicKeyException.class)
                .isThrownBy(() -> registry.register("alice", tooShort, null))
                .withMessageContaining("32 bytes, got 31");
    }

    @Test
    void aPublicKeyThatIsNotBase64IsRejectedAtRegistration() {
        assertThatExceptionOfType(MalformedPublicKeyException.class)
                .isThrownBy(() -> registry.register("alice", "definitely not base64 !!!", null))
                .withMessageContaining("base64");
    }

    // ---- Fixtures -----------------------------------------------------------

    /** A registered approver and the key pair that goes with them. */
    private record Signatory(UUID id, TestApprover keys) {}

    private Withdrawal request(BigInteger amount) {
        return withdrawals.request(new WithdrawalCommand(accountId, DESTINATION, amount, UUID.randomUUID().toString()));
    }

    /** Custodian staff: independent of every client, so they may approve anything. */
    private Signatory staff() {
        return register("staff", null);
    }

    private Signatory register(String name, UUID actsFor) {
        TestApprover keys = TestApprover.generate();
        return new Signatory(registry.register(name, keys.publicKeyBase64(), actsFor).getId(), keys);
    }

    private ApprovalService.ApprovalOutcome approve(Withdrawal withdrawal, Signatory signatory) {
        return approvals.submit(withdrawal.getId(), signatory.id(), signatory.keys().sign(statement(withdrawal)))
                .orElseThrow();
    }

    private static ApprovalStatement statement(Withdrawal withdrawal) {
        return new ApprovalStatement(withdrawal.getId(), withdrawal.getDestination(), withdrawal.getAmount());
    }

    /**
     * 64 zero bytes, for the cases that must fail before verification is reached.
     *
     * <p>Zeros rather than a real signature: if one of those tests ever stops failing where it
     * should, something that verifies against nothing makes the reason obvious.
     */
    private static String unsignedBytes() {
        return Base64.getEncoder().encodeToString(new byte[64]);
    }

    private WithdrawalStatus statusOf(Withdrawal withdrawal) {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus();
    }
}
