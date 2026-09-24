package com.farzam.custody.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A real Ethereum node for the tests, from the image {@code docker-compose.yml} runs.
 *
 * <p><b>Why not a stub, when what is under test is a poller and not a signature.</b> The tempting
 * shortcut here is an in-process HTTP server returning canned receipts, and it would be quicker to
 * write and faster to run. It would also be a test of whether the watcher agrees with the test
 * author's memory of what a receipt looks like. Every field the watcher reads — {@code status} as
 * {@code "0x1"} and not {@code true}, {@code effectiveGasPrice} rather than {@code gasPrice},
 * {@code blockNumber} as a hex quantity, a JSON {@code null} rather than an absent member for a
 * transaction that is not mined — is a place where a stub would confirm the assumption instead of
 * checking it. Anvil starts in about a second and has opinions of its own.
 *
 * <p><b>{@code --no-mining}, unlike the Compose stack and unlike the signer's copy.</b> Blocks are
 * produced only when a test asks, with {@code evm_mine}. Confirmation counting is the thing being
 * tested, and a chain that mines on its own turns "settles at exactly three confirmations" into a
 * race against a two-second timer. It also makes the reorg test possible at all:
 * {@code evm_snapshot} and {@code evm_revert} can roll a mined transaction back out of the chain,
 * which is the one way to produce the case the watcher's receipt store exists for.
 */
public final class AnvilContainer extends GenericContainer<AnvilContainer> {

    private static final int RPC_PORT = 8545;

    public AnvilContainer() {
        super("ghcr.io/foundry-rs/foundry:latest");
        // The image's entrypoint is a shell; anvil is one of several binaries in it.
        withCreateContainerCmdModifier(command -> command.withEntrypoint("anvil"));
        withCommand("--host", "0.0.0.0", "--no-mining");
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
