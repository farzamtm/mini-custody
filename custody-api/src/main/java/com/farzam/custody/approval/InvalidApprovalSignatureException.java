package com.farzam.custody.approval;

import java.io.Serial;
import java.util.UUID;

/**
 * The signature is not this approver's signature over this withdrawal.
 *
 * <p>One exception for every way that can be true, and deliberately so. The signature may be over a
 * different destination, a different amount, a different withdrawal, or it may not be a signature at
 * all; it may have been made with a key this approver does not hold. All of them mean the same thing
 * — this is not evidence that the named person agreed to this payment — and telling a caller which
 * of them it was would be telling them how close their forgery got.
 *
 * <p>The approver id is in the message because it goes into the log, where an operator needs it.
 * {@link com.farzam.custody.web.ApiErrors} does not put it in the response.
 */
public class InvalidApprovalSignatureException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param approverId who the approval claims to be from
     * @param withdrawalId what it claims to approve
     */
    public InvalidApprovalSignatureException(UUID approverId, UUID withdrawalId) {
        super("the signature from " + approverId + " does not verify against withdrawal " + withdrawalId);
    }
}
