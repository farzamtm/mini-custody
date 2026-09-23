package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated, ready-to-book journal transaction.
 *
 * <p>The double-entry rule lives here rather than in {@link LedgerService}, so that it is enforced
 * by the type: if you are holding a {@code Posting}, its entries already sum to zero. Money can be
 * moved, never created or destroyed, and an unbalanced posting cannot reach the database because it
 * cannot be constructed.
 *
 * <p>It is also the only part of the ledger that needs no database, which makes the rule itself
 * unit-testable in microseconds.
 *
 * @param kind the business event
 * @param referenceId what the event is about (a withdrawal id, a deposit id)
 * @param entries at least two lines, summing to zero
 */
public record Posting(JournalKind kind, UUID referenceId, List<Entry> entries) {

    public Posting {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(referenceId, "referenceId");
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries); // defensive: a record component is only as immutable as its value
        if (entries.size() < 2) {
            throw new UnbalancedEntriesException(
                    "a journal transaction needs at least two entries, got " + entries.size());
        }
        BigInteger sum = entries.stream().map(Entry::amount).reduce(BigInteger.ZERO, BigInteger::add);
        if (sum.signum() != 0) {
            throw new UnbalancedEntriesException("entries must sum to zero, they sum to " + sum + " wei");
        }
    }

    /**
     * The net change per account, in ascending account-id order.
     *
     * <p>Two things happen here, and both matter.
     *
     * <p><b>Aggregation.</b> A posting may touch the same account twice; the balance column should
     * move once, by the net amount. The individual entries are still written out in full, because
     * the journal is the audit trail and collapsing it would lose information.
     *
     * <p><b>Ordering.</b> Deadlocks happen when transaction A locks account X then Y while B locks
     * Y then X, and both wait forever. Taking the locks in a globally agreed order — ascending id
     * — makes that cycle impossible to form. This sort is that global order.
     *
     * @return one delta per distinct account, sorted by account id
     */
    public List<Entry> deltas() {
        Map<UUID, BigInteger> netted = new LinkedHashMap<>();
        for (Entry entry : entries) {
            netted.merge(entry.accountId(), entry.amount(), BigInteger::add);
        }
        return netted.entrySet()
                .stream()
                .filter(e -> e.getValue().signum() != 0) // a net-zero account needs no update
                .map(e -> new Entry(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Entry::accountId))
                .toList();
    }
}
