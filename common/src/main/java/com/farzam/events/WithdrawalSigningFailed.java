package com.farzam.events;

import java.util.Objects;
import java.util.UUID;

/**
 * "Refused. Here is why."
 *
 * <p>Published by the signer on {@code signer.results.v1} when its policy says no — a signature that
 * does not verify, an approver it does not trust, a quorum that is short, an amount over the
 * hot-wallet cap. custody-api moves the withdrawal to {@code FAILED} and releases the hold.
 *
 * <p>A refusal is an event, not an error. The signer's alternative is silence, and silence leaves
 * the withdrawal sitting in {@code APPROVED} with the client's money held indefinitely, with nothing
 * to show the client and nothing for an operator to act on. Saying no out loud is what lets the
 * funds go back.
 *
 * <p>{@code reason} is written for a human reading a withdrawal's history. It is the signer's own
 * words about its own decision, so it is safe to store and show; it must not be built from anything
 * the requester supplied.
 *
 * @param withdrawalId which withdrawal
 * @param reason why the signer refused
 */
public record WithdrawalSigningFailed(UUID withdrawalId, String reason) {

    /**
     * @throws NullPointerException if any field is missing
     */
    public WithdrawalSigningFailed {
        Objects.requireNonNull(withdrawalId, "withdrawalId");
        Objects.requireNonNull(reason, "reason");
    }
}
