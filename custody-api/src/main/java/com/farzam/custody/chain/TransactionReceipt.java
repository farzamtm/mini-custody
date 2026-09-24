package com.farzam.custody.chain;

import java.math.BigInteger;
import java.util.Objects;

/**
 * What the chain says happened to a transaction.
 *
 * <p>The four fields this service has a use for, out of the fifteen a receipt carries. Logs, the
 * bloom filter and the cumulative gas belong to contract calls, and this system sends plain
 * transfers.
 *
 * <p><b>{@code successful} is the field people forget.</b> A receipt exists for a transaction that
 * ran out of gas or reverted just as much as for one that worked — being mined and having done what
 * was asked are different questions, and {@code status} is the one that answers the second. A
 * watcher that settled on the existence of a receipt would credit {@code EXTERNAL} for money that
 * never left, while the chain had charged the fee anyway.
 *
 * @param blockNumber the block it was mined in, which is what confirmations are counted from
 * @param successful {@code status == 0x1}. False means it reverted: the fee was still paid and the
 *     value was not transferred
 * @param gasUsed how much gas it actually consumed
 * @param effectiveGasPrice what each unit of that gas cost, after EIP-1559 refunded the difference
 *     between the base fee and the offered maximum. Multiplied by {@code gasUsed}, this is the fee
 *     the hot wallet really paid, which is what the ledger books against {@code BANK_OPERATING}
 */
public record TransactionReceipt(long blockNumber, boolean successful, BigInteger gasUsed,
        BigInteger effectiveGasPrice) {

    /**
     * @throws NullPointerException if either amount is missing
     */
    public TransactionReceipt {
        Objects.requireNonNull(gasUsed, "gasUsed");
        Objects.requireNonNull(effectiveGasPrice, "effectiveGasPrice");
    }

    /**
     * The fee the sender paid for this transaction, in wei.
     *
     * @return {@code gasUsed × effectiveGasPrice}
     */
    public BigInteger feeWei() {
        return gasUsed.multiply(effectiveGasPrice);
    }

    /**
     * How many blocks, including its own, sit on this receipt.
     *
     * <p>Inclusive: a transaction in the head block has one confirmation, not zero. The off-by-one
     * is worth stating because both readings are defensible and only one of them matches what block
     * explorers show, and a watcher that settled a block early would be settling on the block most
     * likely to be reorganised away.
     *
     * @param headBlock the latest mined block
     * @return the depth, never negative — a receipt from a block the node has not caught up to
     *     reads as zero rather than as a negative depth that would compare oddly
     */
    public long confirmationsAt(long headBlock) {
        return Math.max(0, headBlock - blockNumber + 1);
    }
}
