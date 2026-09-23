package com.farzam.custody.ledger;

import java.util.List;

/**
 * Moves the cached balances of a posting's accounts, safely, under concurrency.
 *
 * <p>This is the one interesting seam in the ledger. Everything else — validating that entries sum
 * to zero, writing the journal — is the same whichever way you solve the lost-update problem, so
 * the two solutions live behind this interface and the rest of the code cannot tell them apart.
 * That is what lets the concurrency test run identically against both, which is the only honest way
 * to compare them.
 *
 * <p>Both implementations run inside {@link JournalWriter}'s transaction and neither commits
 * anything itself.
 *
 * @see PessimisticBalanceUpdater the default: {@code SELECT … FOR UPDATE}
 * @see OptimisticBalanceUpdater the alternative: a version check and a retry
 */
interface BalanceUpdater {

    /**
     * Applies one net change per account.
     *
     * @param deltas net changes, already aggregated and sorted by account id — see
     *     {@link Posting#deltas()} for why the order is not cosmetic
     * @throws UnknownAccountException if a delta names an account that does not exist
     * @throws InsufficientFundsException if an account that may not go negative would
     * @throws org.springframework.dao.OptimisticLockingFailureException if a concurrent writer won
     *     the race and the caller should roll back and try again
     */
    void apply(List<Entry> deltas);
}
