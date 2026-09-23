package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the balances without locking anything, then refuses to write if they moved underneath.
 *
 * <p>The alternative, kept so the two can be compared under the same test rather than in the
 * abstract. Switch to it with {@code ledger.locking=optimistic}. See
 * <a href="file:../../../../../../../docs/adr/0001-pessimistic-row-locks-for-ledger-balances.md">ADR
 * 0001</a>.
 *
 * <p>The mechanism is the {@code version} column. Read balance and version; write only
 * {@code where version = :the version I read}. If another transaction committed in between it also
 * bumped the version, the WHERE clause matches nothing, zero rows are updated, and this transaction
 * knows its read was stale. It cannot fix that in place — the affordability check it made is now
 * based on a number that no longer exists — so it throws, the whole transaction rolls back, and
 * {@link LedgerService} starts over with a fresh read.
 *
 * <p>The version check is written out in SQL rather than left to Hibernate's {@code @Version}
 * handling because the ledger's write path does not go through Hibernate at all (ADR 0002). Same
 * mechanism, one fewer layer of indirection between the code and the statement it runs.
 */
class OptimisticBalanceUpdater implements BalanceUpdater {

    /** No {@code FOR UPDATE}: readers do not block and are not blocked. That is the whole point. */
    private static final String READ_ACCOUNTS = """
            select id, type, balance, version
            from accounts
            where id in (:ids)
            order by id
            """;

    /**
     * The compare-and-set. {@code and version = :version} is the entire concurrency control: it
     * turns "write this balance" into "write this balance if nothing has happened since I looked".
     */
    private static final String APPLY_DELTA_IF_UNCHANGED = """
            update accounts
               set balance = balance + :delta,
                   version = version + 1
             where id = :id
               and version = :version
            """;

    private final JdbcClient jdbc;

    OptimisticBalanceUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(List<Entry> deltas) {
        List<UUID> ids = deltas.stream().map(Entry::accountId).toList();

        Map<UUID, VersionedAccount> seen = new LinkedHashMap<>();
        jdbc.sql(READ_ACCOUNTS)
                .param("ids", ids)
                .query(
                        (rs, rowNum) -> new VersionedAccount(
                                rs.getObject("id", UUID.class),
                                AccountType.valueOf(rs.getString("type")),
                                rs.getBigDecimal("balance").toBigIntegerExact(),
                                rs.getLong("version")))
                .list()
                .forEach(account -> seen.put(account.id(), account));

        for (Entry delta : deltas) {
            VersionedAccount account = seen.get(delta.accountId());
            if (account == null) {
                throw new UnknownAccountException(delta.accountId());
            }
            BigInteger after = account.balance().add(delta.amount());
            if (after.signum() < 0 && !account.type().mayGoNegative()) {
                // Not a race: the balance we read was real and it was not enough. Retrying would
                // only read it again, so this propagates instead of being swallowed as a conflict.
                throw new InsufficientFundsException(account.id(), account.balance(), delta.amount().negate());
            }
            int updated = jdbc.sql(APPLY_DELTA_IF_UNCHANGED)
                    .param("delta", delta.amount())
                    .param("id", delta.accountId())
                    .param("version", account.version())
                    .update();
            if (updated == 0) {
                throw new OptimisticLockingFailureException(
                        "account %s changed under us (expected version %d)".formatted(account.id(), account.version()));
            }
        }
    }

    private record VersionedAccount(UUID id, AccountType type, BigInteger balance, long version) {}
}
