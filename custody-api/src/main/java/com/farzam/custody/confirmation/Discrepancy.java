package com.farzam.custody.confirmation;

import java.util.UUID;

/**
 * One thing the ledger and the chain do not agree about.
 *
 * @param withdrawalId which withdrawal
 * @param kind what sort of disagreement
 * @param detail one sentence for a human, containing no client data
 */
public record Discrepancy(UUID withdrawalId, Kind kind, String detail) {

    /**
     * The disagreements worth looking for.
     *
     * <p>Ordered by how bad they are, which is also the order they are worth reading in: the first
     * two mean money has been recorded as gone that the chain does not say has gone, the third means
     * the two halves of a settlement came apart, and the last is an operational nag rather than a
     * discrepancy at all.
     *
     * <p>What is deliberately <em>not</em> here is a check that the hot wallet's on-chain balance
     * matches the ledger. It cannot match in this system and saying so every minute would train
     * everybody to ignore the report: deposits are fabricated by {@code POST /dev/deposits} rather
     * than observed on chain, because nothing here watches for incoming transfers. The balance is
     * reported alongside the findings as information, and closing that loop is a deposit watcher,
     * which is not a milestone in this project.
     */
    public enum Kind {

        /**
         * The ledger settled a withdrawal the chain has no receipt for.
         *
         * <p>The serious one. Settlement moved money out of {@code PENDING_OUT} into
         * {@code EXTERNAL} on the strength of a receipt that has since gone, which means a reorg
         * deeper than {@code chain.confirmations} — or a receipt this service imagined. Either way
         * the ledger is asserting an outflow the chain does not.
         */
        SETTLED_WITHOUT_A_RECEIPT,

        /**
         * The chain has a receipt, and it says the transaction reverted.
         *
         * <p>Should be impossible: the watcher reads {@code status} before it settles. Reaching it
         * means either the receipt changed after settlement, or the status check is broken — and the
         * second is worth a check that does not share code with the thing it is checking.
         */
        SETTLED_A_REVERTED_TRANSACTION,

        /**
         * A confirmed withdrawal with no {@code WITHDRAWAL_SETTLE} behind it.
         *
         * <p>The status and the posting are written in one transaction, so they cannot come apart —
         * unless something wrote a status outside {@link com.farzam.custody.ledger.LedgerService}, or
         * a migration touched a row by hand. Cheap to check, and it is the check that would catch
         * exactly the sort of "quick fix in psql" that leaves a ledger quietly wrong.
         */
        SETTLED_WITHOUT_A_POSTING,

        /**
         * Broadcast a long time ago and still not mined.
         *
         * <p>Not damage, and not necessarily anybody's fault: the transaction may be underpriced, or
         * dropped, or the chain may be busy. The signer resends on its own timer, which fixes the
         * dropped case and does nothing for the underpriced one — repricing needs replacing the
         * transaction at the same nonce with a higher fee, which nothing here does. So this is the
         * finding that says a person should look.
         */
        BROADCAST_BUT_NOT_MINED
    }
}
