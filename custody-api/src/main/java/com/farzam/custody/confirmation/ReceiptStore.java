package com.farzam.custody.confirmation;

import com.farzam.custody.chain.TransactionReceipt;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What this service last saw the chain say about a withdrawal's transaction.
 *
 * <p>Plain SQL rather than JPA, for ADR 0002's reason: this is a table with one row per withdrawal
 * and two statements against it, and an entity would add a mapping to argue with for no reading this
 * code does not already do more clearly.
 *
 * <p><b>The row is overwritten on every tick, not appended to.</b> That is a departure from the
 * ledger's append-only rule and is deliberate: this is not a record of what the system decided, it
 * is a cache of what the chain currently says, and the chain is allowed to change its mind. A
 * receipt that moves from block 100 to block 101 because of a reorg is one fact with two successive
 * values, not two facts. What is append-only is the settlement that results — the journal
 * transaction — and that is booked once by {@code unique (kind, reference_id)} however many times the
 * receipt underneath it is rewritten.
 */
@Component
public class ReceiptStore {

    /**
     * Upsert, because a receipt is re-observed on every tick until it settles.
     *
     * <p>{@code on conflict do update} rather than delete-then-insert: the second is two statements
     * with a window between them in which the row does not exist, and reconciliation running in that
     * window would report a settled withdrawal with no receipt behind it.
     */
    private static final String UPSERT = """
            insert into transaction_receipts
                (withdrawal_id, tx_hash, block_number, succeeded, gas_used, effective_gas_price,
                 confirmations, observed_at)
            values (:withdrawalId, :txHash, :blockNumber, :succeeded, :gasUsed, :effectiveGasPrice,
                    :confirmations, now())
            on conflict (withdrawal_id) do update set
                tx_hash = excluded.tx_hash,
                block_number = excluded.block_number,
                succeeded = excluded.succeeded,
                gas_used = excluded.gas_used,
                effective_gas_price = excluded.effective_gas_price,
                confirmations = excluded.confirmations,
                observed_at = now()
            """;

    private static final String SELECT = """
            select withdrawal_id, tx_hash, block_number, succeeded, gas_used, effective_gas_price,
                   confirmations, observed_at
            from transaction_receipts
            where withdrawal_id = :withdrawalId
            """;

    private static final String DELETE = """
            delete from transaction_receipts where withdrawal_id = :withdrawalId
            """;

    private static final RowMapper<StoredReceipt> AS_STORED = (rs, rowNum) -> new StoredReceipt(
            rs.getObject("withdrawal_id", UUID.class),
            rs.getString("tx_hash"),
            rs.getLong("block_number"),
            rs.getBoolean("succeeded"),
            rs.getBigDecimal("gas_used").toBigIntegerExact(),
            rs.getBigDecimal("effective_gas_price").toBigIntegerExact(),
            rs.getInt("confirmations"),
            rs.getObject("observed_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    ReceiptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records what the chain currently says.
     *
     * @param withdrawalId which withdrawal
     * @param txHash the transaction the receipt is for
     * @param receipt the receipt as read from the node
     * @param confirmations how deep it was, counted against the head block of this pass
     */
    public void observe(UUID withdrawalId, String txHash, TransactionReceipt receipt, int confirmations) {
        jdbc.sql(UPSERT)
                .param("withdrawalId", withdrawalId)
                .param("txHash", txHash)
                .param("blockNumber", receipt.blockNumber())
                .param("succeeded", receipt.successful())
                .param("gasUsed", new BigDecimal(receipt.gasUsed()))
                .param("effectiveGasPrice", new BigDecimal(receipt.effectiveGasPrice()))
                .param("confirmations", confirmations)
                .update();
    }

    /**
     * @param withdrawalId which withdrawal
     * @return the last receipt seen for it, if any
     */
    public Optional<StoredReceipt> find(UUID withdrawalId) {
        return jdbc.sql(SELECT).param("withdrawalId", withdrawalId).query(AS_STORED).optional();
    }

    /**
     * Forgets a receipt the chain no longer has.
     *
     * <p>Only reached when a transaction that was mined has been reorganised back out. Deleting is
     * right rather than keeping a tombstone: the row means "the chain says this", and once the chain
     * does not, the honest state is the one before it ever did — waiting for the signer's resend.
     * That the disappearance happened is a log line and a reconciliation finding, which is where an
     * operator would look for it, rather than a column nothing reads.
     *
     * @param withdrawalId which withdrawal
     */
    public void forget(UUID withdrawalId) {
        jdbc.sql(DELETE).param("withdrawalId", withdrawalId).update();
    }

    /**
     * A receipt as this service last saw it.
     *
     * @param withdrawalId which withdrawal
     * @param txHash the transaction
     * @param blockNumber where it was mined when last observed
     * @param succeeded whether it did what was asked
     * @param gasUsed gas consumed
     * @param effectiveGasPrice price per unit of that gas
     * @param confirmations how deep it was when last looked at
     * @param observedAt when this service last looked
     */
    public record StoredReceipt(UUID withdrawalId, String txHash, long blockNumber, boolean succeeded,
            BigInteger gasUsed, BigInteger effectiveGasPrice, int confirmations, Instant observedAt) {

        /**
         * @return the fee the hot wallet paid, in wei
         */
        public BigInteger feeWei() {
            return gasUsed.multiply(effectiveGasPrice);
        }
    }
}
