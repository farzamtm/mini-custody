package com.farzam.custody.ledger;

/**
 * The four kinds of account in the ledger.
 *
 * <p>Every account belongs to exactly one of these, and the type is what decides whether a
 * negative balance is a bug or the whole point.
 */
public enum AccountType {

    /** What the bank owes one client. One per client. Never negative. */
    CLIENT,

    /** Funds reserved for withdrawals that are not final yet. Never negative. */
    PENDING_OUT,

    /** The bank's own funds, used to pay network fees. Never negative. */
    BANK_OPERATING,

    /**
     * The outside world. A deposit of 1 ETH is EXTERNAL −1 / CLIENT +1: the money came from
     * somewhere, and in double-entry bookkeeping "somewhere" has to be an account. So this one is
     * expected to run negative, and its negation is the amount the hot wallet should hold on
     * chain — the invariant the reconciliation job (M6) checks.
     */
    EXTERNAL;

    /**
     * Whether the ledger may drive this account below zero.
     *
     * <p>The database backs this up with a check constraint, because a rule enforced only in
     * application code is a rule that survives exactly until someone opens psql.
     *
     * @return true only for {@link #EXTERNAL}
     */
    public boolean mayGoNegative() {
        return this == EXTERNAL;
    }
}
