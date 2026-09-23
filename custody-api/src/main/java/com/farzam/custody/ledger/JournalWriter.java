package com.farzam.custody.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One posting, one database transaction.
 *
 * <p>This is deliberately a separate bean from {@link LedgerService} rather than a private method
 * on it. {@code @Transactional} works by putting a proxy in front of the bean, so a call from
 * inside the same object ({@code this.write(…)}) goes straight to the method and the proxy never
 * runs — the classic Spring trap, and a silent one, because the code looks transactional and is
 * not. {@link LedgerService} needs to retry <em>around</em> the transaction (a rolled-back
 * transaction cannot be retried from inside itself), so the boundary has to be a real call to a
 * real other bean.
 *
 * <p>Everything below commits together or not at all: the header row, the balance moves, the
 * entries.
 */
@Component
class JournalWriter {

    /**
     * {@code on conflict do nothing} is the idempotency check, and it is a check the database makes
     * rather than one the application makes.
     *
     * <p>"Look it up, and insert it if it is not there" has a gap between the look-up and the
     * insert, and two threads retrying the same Kafka message will both fit through it. Letting the
     * unique index arbitrate has no gap: exactly one INSERT reports a row, the other reports zero,
     * and neither has to wait for the other to finish first.
     */
    private static final String INSERT_TRANSACTION = """
            insert into journal_transactions (id, kind, reference_id)
            values (:id, :kind, :referenceId)
            on conflict (kind, reference_id) do nothing
            """;

    private static final String FIND_TRANSACTION = """
            select id from journal_transactions where kind = :kind and reference_id = :referenceId
            """;

    private static final String INSERT_ENTRY = """
            insert into journal_entries (transaction_id, account_id, amount)
            values (:transactionId, :accountId, :amount)
            """;

    private final JdbcClient jdbc;
    private final BalanceUpdater balances;

    JournalWriter(JdbcClient jdbc, BalanceUpdater balances) {
        this.jdbc = jdbc;
        this.balances = balances;
    }

    /**
     * Books a posting, or recognises that it has already been booked.
     *
     * @param posting a validated, balanced set of entries
     * @return the id of the journal transaction — the existing one if this is a duplicate
     */
    @Transactional
    public UUID write(Posting posting) {
        UUID transactionId = UUID.randomUUID();

        int inserted = jdbc.sql(INSERT_TRANSACTION)
                .param("id", transactionId)
                .param("kind", posting.kind().name())
                .param("referenceId", posting.referenceId())
                .update();

        if (inserted == 0) {
            // Already booked. Not an error — this is what idempotency looks like from the inside.
            // Returning early is what makes a redelivered event a no-op rather than a double spend.
            return alreadyBooked(posting);
        }

        balances.apply(posting.deltas());

        for (Entry entry : posting.entries()) {
            jdbc.sql(INSERT_ENTRY)
                    .param("transactionId", transactionId)
                    .param("accountId", entry.accountId())
                    .param("amount", entry.amount())
                    .update();
        }

        return transactionId;
    }

    private UUID alreadyBooked(Posting posting) {
        Optional<UUID> existing = jdbc.sql(FIND_TRANSACTION)
                .param("kind", posting.kind().name())
                .param("referenceId", posting.referenceId())
                .query(UUID.class)
                .optional();
        // The row is there — the INSERT above just collided with it — so this cannot be empty
        // unless someone is deleting journal history, which is itself the emergency.
        return existing.orElseThrow(
                () -> new IllegalStateException(
                        "conflict on (%s, %s) but no row to show for it"
                                .formatted(posting.kind(), posting.referenceId())));
    }
}
