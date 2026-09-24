package com.farzam.signer.signing;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The record of every signature this service has produced.
 *
 * <p><b>{@code withdrawal_id} is the primary key, and that is a safety property rather than a
 * schema choice.</b> It is the database refusing, at the storage layer, to let one withdrawal be
 * signed twice — after the idempotent-consumer check has been passed, after the policy has said yes,
 * at the last possible moment before a transaction exists. The consumer guard and this one are not
 * redundant: {@code processed_events} recognises a redelivery of the <em>same</em> event, while this
 * catches a second, differently-identified event about the same withdrawal, which is what a
 * misconfigured custody-api or a replayed topic would produce.
 *
 * <p>Rows are never updated except to stamp {@code broadcast_at}. Nothing rewrites a signature.
 */
@Component
public class SigningLog {

    private static final String INSERT = """
            insert into signing_log (withdrawal_id, tx_hash, raw_tx, nonce)
            values (:withdrawalId, :txHash, :rawTx, :nonce)
            """;

    private static final String SELECT = """
            select withdrawal_id, tx_hash, raw_tx, nonce from signing_log where withdrawal_id = :withdrawalId
            """;

    /**
     * Signed, but not yet known to be on the wire.
     *
     * <p>The age filter keeps the retry job off transactions the listener is about to broadcast
     * itself, a second or two after committing. Without it the job and the listener would race to
     * send the same bytes — harmless, since resending is idempotent, but it would fill the log with
     * "already known" at a steady rate and hide the case worth seeing.
     */
    private static final String UNBROADCAST = """
            select withdrawal_id, tx_hash, raw_tx, nonce
            from signing_log
            where broadcast_at is null and signed_at < now() - cast(:age as interval)
            order by nonce
            limit :limit
            """;

    private static final String MARK_BROADCAST = """
            update signing_log set broadcast_at = now() where withdrawal_id = :withdrawalId
            """;

    private static final RowMapper<SignedTransaction> AS_SIGNED = (rs, rowNum) -> new SignedTransaction(
            rs.getObject("withdrawal_id", UUID.class),
            rs.getString("tx_hash"),
            rs.getString("raw_tx"),
            rs.getLong("nonce"));

    private final JdbcClient jdbc;

    SigningLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a signature.
     *
     * @param signed what was produced
     * @throws org.springframework.dao.DuplicateKeyException if this withdrawal was already signed;
     *     the caller checks first, so reaching this means two transactions raced and the database
     *     settled it
     */
    public void record(SignedTransaction signed) {
        jdbc.sql(INSERT)
                .param("withdrawalId", signed.withdrawalId())
                .param("txHash", signed.txHash())
                .param("rawTx", signed.rawTransaction())
                .param("nonce", signed.nonce())
                .update();
    }

    /**
     * @param withdrawalId which withdrawal
     * @return its signature, if it has one
     */
    public Optional<SignedTransaction> find(UUID withdrawalId) {
        return jdbc.sql(SELECT).param("withdrawalId", withdrawalId).query(AS_SIGNED).optional();
    }

    /**
     * Transactions that were signed and are not known to have reached the network.
     *
     * <p>Ordered by nonce, because a gap blocks everything behind it: resending nonce 8 while 7 is
     * still missing achieves nothing until 7 lands.
     *
     * @param olderThan ignore anything signed more recently than this
     * @param limit how many to take in one pass
     * @return the backlog, lowest nonce first
     */
    public List<SignedTransaction> unbroadcast(Duration olderThan, int limit) {
        return jdbc.sql(UNBROADCAST)
                .param("age", olderThan.toSeconds() + " seconds")
                .param("limit", limit)
                .query(AS_SIGNED)
                .list();
    }

    /**
     * Notes that the network has these bytes.
     *
     * @param withdrawalId which withdrawal
     */
    public void markBroadcast(UUID withdrawalId) {
        jdbc.sql(MARK_BROADCAST).param("withdrawalId", withdrawalId).update();
    }
}
