package com.farzam.custody.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON-RPC client for arranging what the chain says, including the calls only Anvil has.
 *
 * <p><b>Separate from the production {@code EthereumRpc} on purpose.</b> That class is what the
 * tests are checking, so using it to set up their preconditions would let one bug hide another: a
 * broken quantity parser would misread the block number both when the test arranged it and when the
 * watcher read it, and everything would agree. This one is written against {@code java.net.http} and
 * shares no code with it — deliberately including the hex encoding, which is the part most likely to
 * be wrong in an interesting way.
 *
 * <p>The Anvil-only calls are what make the test cases reachable. {@code evm_mine} produces blocks on
 * demand, so "settles at exactly three confirmations" is an assertion rather than a race.
 * {@code evm_snapshot} and {@code evm_revert} roll the chain back, which is the only honest way to
 * produce a transaction that was mined and then was not.
 */
public final class TestChain {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;

    public TestChain(String rpcUrl) {
        this.endpoint = URI.create(rpcUrl);
    }

    /**
     * @return Anvil's unlocked development accounts, the first of which pays for everything here
     */
    public List<String> accounts() {
        return call("eth_accounts").valueStream().map(JsonNode::asText).toList();
    }

    /**
     * Sends a plain transfer from an account Anvil has unlocked.
     *
     * <p>{@code eth_sendTransaction}, not {@code eth_sendRawTransaction}: the node holds the key and
     * signs. custody-api cannot sign and must not be able to, so a test that needs a real transaction
     * on the chain has to get one from somewhere else — and borrowing the node's own accounts is
     * closer to the real thing than borrowing the signer's code would be.
     *
     * <p>With {@code --no-mining} this lands in the mempool and stays there until {@link #mine} is
     * called, which is exactly the state a freshly broadcast withdrawal is in.
     *
     * @param from a funded, unlocked account
     * @param to where the money goes
     * @param wei how much
     * @return the transaction hash
     */
    public String send(String from, String to, BigInteger wei) {
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("from", from);
        transaction.put("to", to);
        transaction.put("value", quantity(wei));
        return call("eth_sendTransaction", transaction).asText();
    }

    /**
     * Deploys five bytes of runtime code whose only behaviour is to revert.
     *
     * <p>{@code PUSH1 0x00, PUSH1 0x00, REVERT}. Sending value to the resulting address produces a
     * receipt with {@code status: "0x0"} — mined, charged for, and the transfer did not happen —
     * which is the case the watcher must not settle and which no amount of arranging plain transfers
     * can produce.
     *
     * <p>The init code around it is the standard shape: put the runtime in memory, return it.
     * {@code MSTORE} right-aligns a 32-byte word, so the five bytes land at offset 27 and that is
     * where {@code RETURN} reads from.
     *
     * @param from a funded, unlocked account
     * @return the address the contract will be deployed at, once a block is mined
     */
    public String deployRevertingContract(String from) {
        Map<String, Object> deployment = new HashMap<>();
        deployment.put("from", from);
        deployment.put("data", "0x6460006000fd6000526005601bf3");
        // An explicit limit, because eth_estimateGas is the node executing the call to find out what
        // it costs — which for deployment is fine, but the habit is set here and the transaction
        // this contract exists to receive cannot be estimated at all: estimating a call that reverts
        // is asking the node how much a failure costs, and it answers by refusing.
        deployment.put("gas", quantity(BigInteger.valueOf(200_000)));
        String txHash = call("eth_sendTransaction", deployment).asText();
        mine(1);
        return call("eth_getTransactionReceipt", txHash).path("contractAddress").asText();
    }

    /**
     * Sends value to an address whose code reverts.
     *
     * @param from a funded, unlocked account
     * @param contract an address deployed by {@link #deployRevertingContract}
     * @param wei how much to try to send
     * @return the transaction hash of a transaction that will be mined and will fail
     */
    public String sendToRevertingContract(String from, String contract, BigInteger wei) {
        Map<String, Object> transaction = new HashMap<>();
        transaction.put("from", from);
        transaction.put("to", contract);
        transaction.put("value", quantity(wei));
        // No estimate is possible for a call that reverts, so the limit is stated. Generous: the
        // contract executes three instructions.
        transaction.put("gas", quantity(BigInteger.valueOf(100_000)));
        return call("eth_sendTransaction", transaction).asText();
    }

    /**
     * @param blocks how many blocks to produce
     */
    public void mine(int blocks) {
        for (int i = 0; i < blocks; i++) {
            call("evm_mine");
        }
    }

    /**
     * @return the latest block number
     */
    public long blockNumber() {
        return Long.decode(call("eth_blockNumber").asText());
    }

    /**
     * @return a handle the chain can be rolled back to
     */
    public String snapshot() {
        return call("evm_snapshot").asText();
    }

    /**
     * Rolls the chain back, taking any transaction mined since the snapshot with it.
     *
     * <p>A reorg, in other words — and a deeper and more total one than a real chain would produce,
     * which is the point: the watcher has to notice that a receipt it had seen is no longer there,
     * and a test that could only arrange a one-block wobble would not exercise that.
     *
     * @param snapshotId a handle from {@link #snapshot}
     */
    public void revertTo(String snapshotId) {
        call("evm_revert", snapshotId);
    }

    /**
     * @param txHash a transaction
     * @return whether the chain has a receipt for it
     */
    public boolean hasReceipt(String txHash) {
        JsonNode receipt = call("eth_getTransactionReceipt", txHash);
        return !receipt.isNull() && !receipt.isMissingNode();
    }

    /**
     * Hex, written here rather than borrowed, for the reason in this class's javadoc.
     */
    private static String quantity(BigInteger value) {
        return "0x" + value.toString(16);
    }

    private JsonNode call(String method, Object... params) {
        try {
            String body = JSON
                    .writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", List.of(params)));
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(endpoint)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = JSON.readTree(response.body());
            if (!parsed.path("error").isMissingNode()) {
                throw new IllegalStateException(method + " failed: " + parsed.path("error"));
            }
            return parsed.path("result");
        } catch (IOException failure) {
            throw new IllegalStateException("could not reach Anvil for " + method, failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling " + method, interrupted);
        }
    }
}
