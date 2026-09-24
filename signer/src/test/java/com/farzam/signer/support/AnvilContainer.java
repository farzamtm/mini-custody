package com.farzam.signer.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A real Ethereum node for the tests, from the image {@code docker-compose.yml} runs.
 *
 * <p>There is no useful stand-in for this one. The thing being tested is whether bytes this service
 * signed are a transaction the EVM accepts — the RLP encoding, the EIP-1559 type byte, the chain id
 * folded into the signature, the recovery parameter, the nonce. A fake node would have to implement
 * all of that to say anything, at which point it is a second Ethereum implementation and the test
 * proves the two agree rather than that either is right. Anvil starts in about a second.
 *
 * <p><b>No {@code --block-time}, unlike the Compose stack.</b> Left alone, Anvil mines a block per
 * transaction, so a test can assert on the destination's balance the moment {@code
 * eth_sendRawTransaction} returns. Compose sets two seconds because M6 needs confirmations to count
 * and a chain that only moves when poked has nothing to count.
 */
public final class AnvilContainer extends GenericContainer<AnvilContainer> {

    private static final int RPC_PORT = 8545;

    public AnvilContainer() {
        super("ghcr.io/foundry-rs/foundry:latest");
        // The image's entrypoint is a shell; anvil is one of several binaries in it.
        withCreateContainerCmdModifier(command -> command.withEntrypoint("anvil"));
        withCommand("--host", "0.0.0.0");
        withExposedPorts(RPC_PORT);
        waitingFor(Wait.forListeningPort());
    }

    /**
     * @return the JSON-RPC endpoint on the randomly-assigned host port
     */
    public String rpcUrl() {
        return "http://" + getHost() + ":" + getMappedPort(RPC_PORT);
    }
}
