package com.farzam.custody.approval;

import java.io.Serial;
import java.util.UUID;

/**
 * This approver has already signed off on this withdrawal.
 *
 * <p>A {@code 409}, not a silent success, and the choice is worth stating because "record it again"
 * is genuinely tempting — the second row would be rejected by the primary key anyway, so nothing
 * would break. What would break is the answer this API gives back: the response says how many
 * approvals have been collected and how many are needed, and an approver who submits twice and is
 * told "accepted, one of two" has been told their signature landed when it did not change anything.
 * On the request that is supposed to make the difference between one signature and two, that is
 * exactly the wrong thing to be vague about.
 *
 * <p>It is not idempotency either. A retried HTTP request is indistinguishable from a deliberate
 * second submission here, and unlike a withdrawal, an approval has no client-chosen key to tell them
 * apart — so the honest answer to both is that it has already happened.
 */
public class AlreadyApprovedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param approverId who has already approved
     * @param withdrawalId what they have already approved
     */
    public AlreadyApprovedException(UUID approverId, UUID withdrawalId) {
        super("approver " + approverId + " has already approved withdrawal " + withdrawalId);
    }
}
