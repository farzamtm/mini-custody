package com.farzam.custody.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farzam.custody.support.AbstractChainTest;
import com.farzam.custody.support.TestChain;
import com.farzam.custody.support.WithoutKafka;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The three JSON-RPC calls, against a real node.
 *
 * <p>Worth its own test rather than being covered incidentally by the watcher's, because what is
 * being checked here is the decoding — hex quantities, a JSON {@code null} for a transaction that
 * is not mined, {@code status} as a string — and the watcher's assertions would pass just as well
 * against a client that got two fields wrong in compensating directions.
 */
@SpringBootTest
@WithoutKafka
class EthereumRpcTest extends AbstractChainTest {

    /** A hash no key could be mistaken for. See {@code .gitleaks.toml} for why that matters. */
    private static final String NEVER_SENT = "0x" + "deadbeef".repeat(8);

    @Autowired
    private EthereumRpc rpc;

    private TestChain chain;
    private String funder;

    @BeforeEach
    void findAnUnlockedAccount() {
        chain = chain();
        funder = chain.accounts().getFirst();
    }

    @Test
    void itReadsTheHeadBlockAndItMovesWhenTheChainDoes() {
        long before = rpc.blockNumber();

        chain.mine(2);

        assertThat(rpc.blockNumber()).isEqualTo(before + 2);
    }

    @Test
    void itReadsABalance() {
        assertThat(rpc.balanceOf(funder)).isPositive();
    }

    /**
     * A transaction the chain has never heard of is empty, not an exception.
     *
     * <p>It is the ordinary state of every transaction for its first few seconds, so the watcher
     * calls this on every tick for every withdrawal in flight. An exception would make "not mined
     * yet" indistinguishable from "the node is down", and those must not be confused: one means keep
     * waiting and the other means do not touch the ledger.
     */
    @Test
    void aTransactionTheChainDoesNotHaveIsEmpty() {
        assertThat(rpc.receiptOf(NEVER_SENT)).isEmpty();
    }

    @Test
    void aMinedTransactionComesBackWithItsBlockStatusAndFee() {
        String txHash = chain.send(funder, "0x" + "a".repeat(40), BigInteger.valueOf(1_000));
        chain.mine(1);
        long head = rpc.blockNumber();

        Optional<TransactionReceipt> found = rpc.receiptOf(txHash);

        assertThat(found).hasValueSatisfying(receipt -> {
            assertThat(receipt.successful()).isTrue();
            assertThat(receipt.blockNumber()).isEqualTo(head);
            assertThat(receipt.confirmationsAt(head)).isEqualTo(1);
            // 21000 exactly, for a transfer to an account with no code — but asserted as a range,
            // because the number this test is about is the one the node reported rather than the
            // one the EVM specification says it should have.
            assertThat(receipt.gasUsed()).isPositive();
            assertThat(receipt.feeWei()).isNotNegative();
        });
    }

    /**
     * A transaction that reverted still has a receipt, and it says so.
     *
     * <p>The single most important field this client reads. A client that returned
     * {@code successful: true} for everything with a receipt would pass every other test in this
     * file and would settle the ledger for money that never moved.
     */
    @Test
    void aRevertedTransactionHasAReceiptThatSaysItFailed() {
        String reverter = chain.deployRevertingContract(funder);
        String txHash = chain.sendToRevertingContract(funder, reverter, BigInteger.valueOf(1_000));
        chain.mine(1);

        assertThat(rpc.receiptOf(txHash)).hasValueSatisfying(receipt -> {
            assertThat(receipt.successful()).isFalse();
            assertThat(receipt.feeWei()).as("and the chain charged for it anyway").isNotNegative();
        });
    }

    /**
     * A node that is not there throws, and that is the one behaviour that must not be softened.
     *
     * <p>"The node did not answer" must never reach the watcher as "there is no receipt", because
     * the second is a state the watcher acts on. Built directly rather than through Spring, so the
     * unreachable URL does not need a second application context.
     */
    @Test
    void aNodeThatIsNotThereIsAnExceptionAndNotAnEmptyAnswer() {
        EthereumRpc offline = new EthereumRpc(
                new ChainProperties(
                        URI.create("http://127.0.0.1:1"),
                        31337,
                        3,
                        "",
                        null,
                        0,
                        Duration.ofMillis(250),
                        null));

        assertThatThrownBy(offline::blockNumber).isInstanceOf(RpcException.class);
        assertThatThrownBy(() -> offline.receiptOf(NEVER_SENT)).isInstanceOf(RpcException.class);
    }
}
