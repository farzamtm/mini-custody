package com.farzam.signer.chain;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which chain, and how this service talks to it.
 *
 * @param rpcUrl the JSON-RPC endpoint; Anvil locally
 * @param chainId the network. Signed into every transaction by EIP-155, which is what stops a
 *     transaction signed for a testnet being replayed on mainnet — the signature does not verify
 *     against a different chain id. Anvil is 31337.
 * @param gasLimit 21000, the exact cost of a plain ETH transfer. A constant rather than an
 *     {@code eth_estimateGas} call because there is nothing to estimate: a transfer to an
 *     externally-owned account executes no code. It becomes a call the day this signs a token
 *     transfer, where the cost depends on the contract's storage.
 * @param fallbackPriorityFeeWei the tip to offer when the node will not suggest one. 1 gwei, which
 *     is generous on a local chain and would be a reasonable floor on a quiet mainnet.
 * @param rpcTimeout how long to wait for the node. Short, because the two calls on the signing path
 *     hold a database transaction open while they run.
 */
@ConfigurationProperties("chain")
public record ChainProperties(URI rpcUrl, long chainId, BigInteger gasLimit, BigInteger fallbackPriorityFeeWei,
        Duration rpcTimeout) {

    private static final BigInteger PLAIN_TRANSFER_GAS = BigInteger.valueOf(21_000);

    private static final BigInteger ONE_GWEI = BigInteger.valueOf(1_000_000_000);

    /**
     * Fills in everything that has a sensible answer, so {@code application.yml} only states what is
     * genuinely a deployment decision: the URL and the chain id.
     */
    public ChainProperties {
        gasLimit = gasLimit == null ? PLAIN_TRANSFER_GAS : gasLimit;
        fallbackPriorityFeeWei = fallbackPriorityFeeWei == null ? ONE_GWEI : fallbackPriorityFeeWei;
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(5) : rpcTimeout;
    }
}
