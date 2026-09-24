package com.farzam.signer.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farzam.signer.support.AbstractSignerTest;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The four JSON-RPC calls, against a real node, plus what happens when there is not one. */
@SpringBootTest
class EthereumRpcTest extends AbstractSignerTest {

    @Autowired
    private EthereumRpc rpc;

    @Test
    void itReadsTheHotWalletsBalanceAndTransactionCount() {
        assertThat(rpc.balanceOf(HOT_WALLET_ADDRESS)).isPositive();
        assertThat(rpc.transactionCount(HOT_WALLET_ADDRESS)).isNotNegative();
    }

    /**
     * {@code maxFeePerGas} has to cover the base fee with room to spare, or the transaction is valid
     * in this block and unmineable in the next one.
     */
    @Test
    void theFeesItSuggestsLeaveHeadroomOverTheTip() {
        Fees fees = rpc.currentFees();

        assertThat(fees.maxPriorityFeePerGas()).isNotNegative();
        assertThat(fees.maxFeePerGas()).isGreaterThanOrEqualTo(fees.maxPriorityFeePerGas());
    }

    @Test
    void bytesTheNodeRefusesAreAnRpcExceptionRatherThanASilentFailure() {
        assertThatThrownBy(() -> rpc.sendRawTransaction("0xdeadbeef", "0x" + "00".repeat(32)))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining("refused");
    }

    /**
     * An unreachable node must be an exception the listener can be retried on, not a null that
     * turns into a transaction signed with a nonce read from nowhere.
     */
    @Test
    void anUnreachableNodeIsAnRpcException() {
        var nowhere = new EthereumRpc(
                new ChainProperties(URI.create("http://127.0.0.1:1"), 31337, null, null, Duration.ofMillis(250)));

        assertThatThrownBy(() -> nowhere.balanceOf(HOT_WALLET_ADDRESS)).isInstanceOf(RpcException.class)
                .hasMessageContaining("could not reach the node");
    }

    @Test
    void theDefaultsFillInEverythingThatIsNotADeploymentDecision() {
        var defaults = new ChainProperties(URI.create("http://localhost:8545"), 31337, null, null, null);

        assertThat(defaults.gasLimit()).isEqualTo(BigInteger.valueOf(21_000));
        assertThat(defaults.fallbackPriorityFeeWei()).isEqualTo(BigInteger.valueOf(1_000_000_000));
        assertThat(defaults.rpcTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
