package com.farzam.custody.confirmation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What one reconciliation run found.
 *
 * <p>The counts are there so an empty {@code discrepancies} list can be told apart from a run that
 * checked nothing. "No problems found" and "no withdrawals exist" produce the same empty list and
 * mean entirely different things, and a report that could not distinguish them would be reassuring
 * for the wrong reason.
 *
 * @param checkedAt when the run finished
 * @param confirmedChecked how many settled withdrawals were compared against the chain
 * @param inFlightChecked how many broadcast withdrawals were looked at
 * @param approvedChecked how many approved-but-unbroadcast withdrawals were looked at. Free, unlike
 *     the other two: there is no transaction to ask the chain about yet, which is the problem
 * @param hotWalletBalanceWei what the signer's wallet holds on chain, if an address is configured.
 *     Reported, never asserted on — see {@link Reconciler} for why it cannot be reconciled until
 *     deposits are observed on chain rather than fabricated
 * @param discrepancies what disagreed, worst first
 */
public record ReconciliationReport(Instant checkedAt, int confirmedChecked, int inFlightChecked, int approvedChecked,
        Optional<BigInteger> hotWalletBalanceWei, List<Discrepancy> discrepancies) {

    /**
     * @throws NullPointerException if any field is missing
     */
    public ReconciliationReport {
        discrepancies = List.copyOf(discrepancies);
    }

    /**
     * @return true if the ledger and the chain tell the same story
     */
    public boolean agrees() {
        return discrepancies.isEmpty();
    }
}
