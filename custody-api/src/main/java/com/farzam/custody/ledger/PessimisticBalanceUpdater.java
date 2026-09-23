package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Takes a write lock on every account the posting touches, then moves the balances.
 *
 * <p>The default. See <a href="file:../../../../../../../docs/adr/0001-pessimistic-row-locks-for-ledger-balances.md">ADR
 * 0001</a> for why.
 *
 * <p>The shape is: one {@code SELECT … FOR UPDATE} that locks all the rows in one round trip, the
 * affordability check against the balances that select returned, then one UPDATE per account. Any
 * other transaction touching the same accounts waits at the SELECT until this one commits, so it
 * can never read a balance that is about to change.
 */
class PessimisticBalanceUpdater implements BalanceUpdater {

    /**
     * {@code order by id} is load-bearing, not tidiness.
     *
     * <p>Postgres takes the row locks in the order the rows come out of the plan. Fixing that order
     * globally — ascending id, everywhere, for every posting — means two transactions contending
     * for the same pair of accounts always approach them from the same side. The classic deadlock
     * (A holds X waiting for Y, B holds Y waiting for X) has no way to form. {@link Posting#deltas()}
     * sorts the updates the same way for the same reason.
     *
     * <p>Without {@code NOWAIT} or {@code SKIP LOCKED} this blocks rather than fails, which is
     * exactly what is wanted for a balance: the second writer should proceed once the first is done,
     * not be told to come back later.
     */
    private static final String LOCK_ACCOUNTS = """
            select id, type, balance
            from accounts
            where id in (:ids)
            order by id
            for update
            """;

    private static final String APPLY_DELTA = """
            update accounts
               set balance = balance + :delta,
                   version = version + 1
             where id = :id
            """;

    private final JdbcClient jdbc;

    PessimisticBalanceUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void apply(List<Entry> deltas) {
        List<UUID> ids = deltas.stream().map(Entry::accountId).toList();

        Map<UUID, LockedAccount> locked = new LinkedHashMap<>();
        jdbc.sql(LOCK_ACCOUNTS)
                .param("ids", ids)
                .query(
                        (rs, rowNum) -> new LockedAccount(
                                rs.getObject("id", UUID.class),
                                AccountType.valueOf(rs.getString("type")),
                                rs.getBigDecimal("balance").toBigIntegerExact()))
                .list()
                .forEach(account -> locked.put(account.id(), account));

        // From here to commit, nobody else can change these rows.
        for (Entry delta : deltas) {
            LockedAccount account = locked.get(delta.accountId());
            if (account == null) {
                throw new UnknownAccountException(delta.accountId());
            }
            BigInteger after = account.balance().add(delta.amount());
            if (after.signum() < 0 && !account.type().mayGoNegative()) {
                throw new InsufficientFundsException(account.id(), account.balance(), delta.amount().negate());
            }
            jdbc.sql(APPLY_DELTA).param("delta", delta.amount()).param("id", delta.accountId()).update();
        }
    }

    /** The three columns the decision needs, read while the row is locked. */
    private record LockedAccount(UUID id, AccountType type, BigInteger balance) {}
}
