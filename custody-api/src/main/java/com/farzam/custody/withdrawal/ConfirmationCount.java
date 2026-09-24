package com.farzam.custody.withdrawal;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * How deep a withdrawal's transaction is, for the response to say so.
 *
 * <p><b>An interface here rather than a direct call, and it is about direction rather than
 * abstraction.</b> The confirmation package already depends on this one — it loads withdrawals,
 * moves them and settles them — so a controller here reaching into it would make the two mutually
 * dependent, and the pair would have to be read as one thing to be understood. Declaring the
 * narrow thing this package needs and letting the other one implement it keeps the arrow pointing
 * one way. There is exactly one implementation and there is not expected to be a second; the
 * interface is not here for substitutability.
 *
 * <p>It is deliberately the smallest possible surface. The confirmation package knows about receipts,
 * blocks, gas and reorgs, and none of that belongs in a withdrawal's representation — what a client
 * polling a withdrawal wants is one number.
 */
public interface ConfirmationCount {

    /**
     * @param withdrawalId which withdrawal
     * @return how many blocks sat on its receipt when the watcher last looked, or empty if the chain
     *     has no receipt for it — which covers "still in the mempool", "dropped" and "reorganised
     *     out" alike, none of which a client can act on differently
     */
    OptionalInt forWithdrawal(UUID withdrawalId);
}
