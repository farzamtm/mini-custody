package com.farzam.custody.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * The two pieces of arithmetic a settlement depends on, without a chain or a context.
 *
 * <p>Both are one line, and both decide whether money moves. The confirmation count decides
 * <em>when</em>, and an off-by-one there settles a block early — on the block most likely to be
 * reorganised away. The fee decides <em>how much</em> comes out of the bank's account, and it is a
 * multiplication of two numbers that are each large enough to overflow anything narrower than a
 * {@code BigInteger}.
 */
class TransactionReceiptTest {

    private static final BigInteger GAS_USED = BigInteger.valueOf(21_000);
    private static final BigInteger ONE_GWEI = BigInteger.valueOf(1_000_000_000);

    private final TransactionReceipt receipt = new TransactionReceipt(100, true, GAS_USED, ONE_GWEI);

    /**
     * A transaction in the head block has one confirmation, not zero.
     *
     * <p>Both readings are defensible and only one matches what a block explorer shows. The
     * consequence of the other is settling a block early, every time.
     */
    @Test
    void aReceiptInTheHeadBlockHasOneConfirmation() {
        assertThat(receipt.confirmationsAt(100)).isEqualTo(1);
    }

    @Test
    void eachBlockOnTopIsOneMore() {
        assertThat(receipt.confirmationsAt(101)).isEqualTo(2);
        assertThat(receipt.confirmationsAt(102)).isEqualTo(3);
    }

    /**
     * A head behind the receipt reads as zero rather than as a negative depth.
     *
     * <p>Reachable when a load-balanced RPC endpoint puts two calls on nodes at different heights,
     * which is an ordinary thing for a provider to do. A negative number here would compare as
     * "fewer than three confirmations" and behave correctly by accident; zero says the same thing on
     * purpose.
     */
    @Test
    void aHeadBehindTheReceiptIsZeroRatherThanNegative() {
        assertThat(receipt.confirmationsAt(99)).isZero();
        assertThat(receipt.confirmationsAt(0)).isZero();
    }

    @Test
    void theFeeIsGasUsedTimesWhatItActuallyCost() {
        assertThat(receipt.feeWei()).isEqualTo(new BigInteger("21000000000000"));
    }

    /**
     * Wei times gas exceeds a {@code long}, and this is the class where it would.
     *
     * <p>21,000 gas at 1,000 gwei is 2.1 × 10^16, which fits; the same gas at the prices a
     * congested chain has seen does not stay comfortable for long, and the whole reason this project
     * bans {@code double} applies to {@code long} here too.
     */
    @Test
    void aLargeFeeDoesNotOverflow() {
        BigInteger huge = new BigInteger("1000000000000000000000");

        assertThat(new TransactionReceipt(1, true, huge, huge).feeWei()).isEqualTo(huge.multiply(huge));
    }

    @Test
    void aReceiptWithoutItsAmountsIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> new TransactionReceipt(1, true, null, ONE_GWEI));
        assertThatNullPointerException().isThrownBy(() -> new TransactionReceipt(1, true, GAS_USED, null));
    }
}
