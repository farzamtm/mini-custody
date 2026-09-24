package com.farzam.signer.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farzam.events.ApprovalStatement;
import com.farzam.events.WithdrawalApproved;
import com.farzam.signer.support.TestApprover;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Everything the signer refuses, and why, without a database or a chain.
 *
 * <p>The end-to-end test proves the refusals reach custody-api. These prove the decisions
 * themselves, which is where the security argument lives and where an exhaustive set of cases is
 * affordable: each of these runs in microseconds, and each is a way somebody would try to get this
 * service to sign something it should not.
 */
class SigningPolicyTest {

    private static final BigInteger HALF_ETH = new BigInteger("500000000000000000");

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");

    private static final BigInteger TWO_ETH = new BigInteger("2000000000000000000");

    private static final BigInteger CAP = new BigInteger("5000000000000000000");

    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    private final TestApprover alice = TestApprover.generate();
    private final TestApprover bob = TestApprover.generate();
    private final TestApprover outsider = TestApprover.generate();

    private final SigningPolicy policy = new SigningPolicy(
            new PolicyProperties(
                    CAP,
                    ONE_ETH,
                    List.of(
                            new PolicyProperties.TrustedApprover(alice.id(), alice.publicKeyBase64()),
                            new PolicyProperties.TrustedApprover(bob.id(), bob.publicKeyBase64()))));

    @Test
    void oneTrustedApprovalIsEnoughBelowTheThreshold() {
        UUID id = UUID.randomUUID();

        assertThatCode(() -> policy.check(event(id, HALF_ETH, List.of(alice.approve(id, DESTINATION, HALF_ETH)))))
                .doesNotThrowAnyException();
    }

    @Test
    void twoAreNeededAtOrAboveIt() {
        assertThat(policy.requiredApprovals(HALF_ETH)).isOne();
        // At the threshold, not merely above it: "2 ETH needs two approvers" is easy, and the
        // boundary is where an off-by-one lets exactly-one-ETH through on a single signature.
        assertThat(policy.requiredApprovals(ONE_ETH)).isEqualTo(2);
        assertThat(policy.requiredApprovals(TWO_ETH)).isEqualTo(2);
    }

    @Test
    void twoDistinctTrustedApproversSatisfyALargeWithdrawal() {
        UUID id = UUID.randomUUID();

        assertThatCode(
                () -> policy.check(
                        event(
                                id,
                                TWO_ETH,
                                List.of(
                                        alice.approve(id, DESTINATION, TWO_ETH),
                                        bob.approve(id, DESTINATION, TWO_ETH)))))
                .doesNotThrowAnyException();
    }

    /**
     * The check that makes four-eyes mean two people. Counting approvals rather than approvers would
     * let one of them satisfy a two-approver quorum by sending their valid signature twice, which is
     * a copy-paste away and defeats the entire control.
     */
    @Test
    void theSameApproverTwiceIsStillOneApprover() {
        UUID id = UUID.randomUUID();
        WithdrawalApproved.Approval approval = alice.approve(id, DESTINATION, TWO_ETH);

        assertThatThrownBy(() -> policy.check(event(id, TWO_ETH, List.of(approval, approval))))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("needs 2 valid approvals");
    }

    /**
     * The realistic forgery: a genuine signature by a trusted approver, over a statement about a
     * different amount, replayed onto a larger event.
     */
    @Test
    void aSignatureOverDifferentFactsIsNotAnApprovalOfThese() {
        UUID id = UUID.randomUUID();
        var signedForATrifle = new ApprovalStatement(id, DESTINATION, BigInteger.ONE);

        assertThatThrownBy(() -> policy.check(event(id, HALF_ETH, List.of(alice.approve(signedForATrifle)))))
                .isInstanceOf(SigningRefusedException.class);
    }

    @Test
    void aSignatureOverADifferentDestinationIsNotAnApprovalOfThisOne() {
        UUID id = UUID.randomUUID();
        var signedForElsewhere = new ApprovalStatement(id, "0x" + "b".repeat(40), HALF_ETH);

        assertThatThrownBy(() -> policy.check(event(id, HALF_ETH, List.of(alice.approve(signedForElsewhere)))))
                .isInstanceOf(SigningRefusedException.class);
    }

    /** Valid arithmetic, worthless as an approval: the key is not on this service's list. */
    @Test
    void aFlawlessSignatureFromAnUntrustedKeyCountsForNothing() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(
                () -> policy.check(event(id, HALF_ETH, List.of(outsider.approve(id, DESTINATION, HALF_ETH)))))
                .isInstanceOf(SigningRefusedException.class);
    }

    @Test
    void aTamperedSignatureFromATrustedApproverCountsForNothing() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(
                () -> policy.check(
                        event(
                                id,
                                HALF_ETH,
                                List.of(alice.garbledApproval(new ApprovalStatement(id, DESTINATION, HALF_ETH))))))
                .isInstanceOf(SigningRefusedException.class);
    }

    /** Base64 that is not base64 has to be a refusal, not a 500 in the listener. */
    @Test
    void aSignatureThatIsNotEvenBase64IsARefusal() {
        UUID id = UUID.randomUUID();
        var approval = new WithdrawalApproved.Approval(alice.id(), alice.publicKeyBase64(), "not base64 !!");

        assertThatThrownBy(() -> policy.check(event(id, HALF_ETH, List.of(approval))))
                .isInstanceOf(SigningRefusedException.class);
    }

    @Test
    void noApprovalsAtAllIsARefusalRatherThanAnEmptyQuorum() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> policy.check(event(id, HALF_ETH, List.of())))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("has 0");
    }

    @Test
    void anAmountOverTheCapIsRefusedNoMatterHowManyApproveIt() {
        UUID id = UUID.randomUUID();
        BigInteger overCap = CAP.add(BigInteger.ONE);

        assertThatThrownBy(
                () -> policy.check(
                        event(
                                id,
                                overCap,
                                List.of(
                                        alice.approve(id, DESTINATION, overCap),
                                        bob.approve(id, DESTINATION, overCap)))))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("per-transaction limit");
    }

    @Test
    void aMalformedDestinationIsRefusedBeforeAnythingElseIsChecked() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> policy.check(new WithdrawalApproved(id, "0xnot-an-address", HALF_ETH, List.of())))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("well-formed Ethereum address");
    }

    @Test
    void aZeroOrNegativeAmountIsRefused() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> policy.check(event(id, BigInteger.ZERO, List.of())))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("not positive");
        assertThatThrownBy(() -> policy.check(event(id, BigInteger.valueOf(-1), List.of())))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("not positive");
    }

    /**
     * The state a freshly deployed signer is in, and the whole of why custody-api's approver table
     * cannot be used to move funds: an approval list that does not appear in <em>this</em>
     * configuration counts for nothing, however well signed it is.
     */
    @Test
    void aSignerWithNoTrustedApproversRefusesEverything() {
        var closed = new SigningPolicy(new PolicyProperties(CAP, ONE_ETH, List.of()));
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> closed.check(event(id, HALF_ETH, List.of(alice.approve(id, DESTINATION, HALF_ETH)))))
                .isInstanceOf(SigningRefusedException.class);
    }

    /** Absent policy properties mean "refuse", never "allow". */
    @Test
    void anUnconfiguredPolicyFailsClosed() {
        var unconfigured = new SigningPolicy(new PolicyProperties(null, null, null));
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> unconfigured.check(event(id, BigInteger.ONE, List.of())))
                .isInstanceOf(SigningRefusedException.class)
                .hasMessageContaining("per-transaction limit of 0 wei");
    }

    /** A key that will not parse is a deployment that should not start. */
    @Test
    void aMalformedTrustedKeyStopsTheServiceRatherThanBeingSkipped() {
        var broken = new PolicyProperties(
                CAP,
                ONE_ETH,
                List.of(new PolicyProperties.TrustedApprover(UUID.randomUUID(), "bm90IGEga2V5")));

        assertThatThrownBy(() -> new SigningPolicy(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid Ed25519 key");
    }

    private static WithdrawalApproved event(
            UUID withdrawalId,
            BigInteger amountWei,
            List<WithdrawalApproved.Approval> approvals) {
        return new WithdrawalApproved(withdrawalId, DESTINATION, amountWei, approvals);
    }
}
