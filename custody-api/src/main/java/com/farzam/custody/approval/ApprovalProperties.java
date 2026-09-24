package com.farzam.custody.approval;

import java.math.BigInteger;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many approvers an amount needs.
 *
 * <p><b>This duplicates {@code signer.policy.second-approval-from-wei}, and the duplication is the
 * security model rather than an oversight.</b> custody-api enforces the quorum before it will
 * publish anything, and the signer enforces it again from its own configuration before it will sign.
 * If the signer trusted this service to have counted, then owning this service would be enough to
 * move funds — which is the exact scenario the split exists to survive. Two independent checks of
 * the same rule is what "defence in depth" means when it is not a slogan.
 *
 * <p>The two copies can be set to different numbers, and it is worth knowing what happens if they
 * are. The stricter one wins, because both have to pass. A custody-api configured more loosely than
 * the signer produces withdrawals that get approved here and refused there — visible in the
 * withdrawal's failure reason, with the hold released. The reverse is simply a stricter system than
 * the signer knows about. Neither ends with a transaction nobody approved.
 *
 * @param secondApprovalFromWei the four-eyes threshold. At or above this, two distinct approvers are
 *     required; below it, one. The point is not that small withdrawals are safe — it is that a rule
 *     expensive enough to be routinely worked around protects nothing, so the expensive rule is kept
 *     for the amounts that justify it.
 */
@ConfigurationProperties("approvals")
public record ApprovalProperties(BigInteger secondApprovalFromWei) {

    /** Two approvers, for an amount that needs a second pair of eyes. */
    private static final int FOUR_EYES = 2;

    /** One approver, below the threshold. */
    private static final int TWO_EYES = 1;

    /**
     * A missing threshold means zero, which means everything needs two approvers.
     *
     * <p>Fail-closed, and in the strict direction on purpose: the other reading of an absent property
     * is a system that quietly accepts a single signature for any amount. A deployment that has
     * forgotten to configure its quorum should be inconvenient, not permissive.
     */
    public ApprovalProperties {
        secondApprovalFromWei = secondApprovalFromWei == null ? BigInteger.ZERO : secondApprovalFromWei;
    }

    /**
     * How many distinct approvers this amount needs.
     *
     * @param amountWei the withdrawal amount
     * @return two at or above the threshold, one below it
     */
    public int requiredApprovals(BigInteger amountWei) {
        return amountWei.compareTo(secondApprovalFromWei) >= 0 ? FOUR_EYES : TWO_EYES;
    }
}
