package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a journal transaction: move {@code amount} wei into {@code accountId}.
 *
 * <p>The amount is signed. Negative is a debit, positive a credit, and the entries of one
 * transaction must sum to zero — see {@link Posting}.
 *
 * <p>{@link BigInteger} rather than {@code long} because an Ethereum amount can reach 2^256, which
 * is 78 decimal digits. It has no arithmetic operators, so this codebase reads
 * {@code a.add(b)} and {@code a.compareTo(b) >= 0} everywhere money is involved. That is a feature:
 * there is no way to accidentally write {@code 0.1 + 0.2} and get 0.30000000000000004.
 *
 * @param accountId the account being moved
 * @param amount signed wei; negative debits the account, positive credits it
 */
public record Entry(UUID accountId, BigInteger amount) {

    /** Compact constructor: an entry that is null anywhere, or worth nothing, is a bug upstream. */
    public Entry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() == 0) {
            throw new UnbalancedEntriesException("a journal entry of zero moves no money: " + accountId);
        }
    }
}
