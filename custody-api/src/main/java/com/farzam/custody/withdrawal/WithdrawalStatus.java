package com.farzam.custody.withdrawal;

/**
 * Where a withdrawal is, and where it is allowed to go next.
 *
 * <pre>
 *   [*] → PENDING_APPROVAL → APPROVED → BROADCAST → CONFIRMED → [*]
 *              ↓                 ↓          ↓
 *          REJECTED           FAILED     FAILED → [*]
 * </pre>
 *
 * <p>The transition table lives on the enum rather than in a service, so there is exactly one
 * answer to "can this move happen" and it is available to anyone holding a status. The entity calls
 * it in {@link Withdrawal#moveTo}; nothing else may assign a status.
 *
 * <p>Both methods are {@code switch} <em>expressions</em> with no {@code default}. That is
 * deliberate: an expression switch over an enum must be exhaustive, so adding a seventh status
 * makes this file stop compiling and lists exactly the decisions that have not been made yet. A
 * {@code default} would silently answer for the new case, and the answer would be wrong.
 */
public enum WithdrawalStatus {

    /** Funds are held. Waiting for the quorum of approvals (M3). */
    PENDING_APPROVAL,

    /** Approved and handed to the signer over Kafka (M4). */
    APPROVED,

    /** The signer has a transaction hash on chain (M5). */
    BROADCAST,

    /** Enough confirmations. The hold has been settled against EXTERNAL (M6). */
    CONFIRMED,

    /** Turned down before it was ever signed. The hold goes back to the client. */
    REJECTED,

    /** The signer refused, or the transaction reverted. The hold goes back to the client. */
    FAILED;

    /**
     * Whether this status may become {@code next}.
     *
     * <p>Note what is absent: there is no path back out of {@link #CONFIRMED}, {@link #REJECTED} or
     * {@link #FAILED}, and no way to skip {@link #BROADCAST}. A withdrawal that reached the chain
     * cannot be un-sent, so a state machine that allowed the reverse would be describing something
     * the world cannot do.
     *
     * @param next the proposed status
     * @return true if the move is legal
     */
    public boolean canMoveTo(WithdrawalStatus next) {
        return switch (this) {
            case PENDING_APPROVAL -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == BROADCAST || next == FAILED;
            case BROADCAST -> next == CONFIRMED || next == FAILED;
            case CONFIRMED, REJECTED, FAILED -> false;
        };
    }

    /**
     * Whether the withdrawal is finished, however it ended.
     *
     * @return true for {@link #CONFIRMED}, {@link #REJECTED} and {@link #FAILED}
     */
    public boolean isTerminal() {
        return switch (this) {
            case CONFIRMED, REJECTED, FAILED -> true;
            case PENDING_APPROVAL, APPROVED, BROADCAST -> false;
        };
    }

    /**
     * Whether reaching this status means the held funds go back to the client.
     *
     * <p>{@link #CONFIRMED} is the other terminal outcome and settles the hold against EXTERNAL
     * instead — the money really did leave. The distinction is the whole reason a hold exists.
     *
     * @return true for {@link #REJECTED} and {@link #FAILED}
     */
    public boolean releasesTheHold() {
        return this == REJECTED || this == FAILED;
    }
}
