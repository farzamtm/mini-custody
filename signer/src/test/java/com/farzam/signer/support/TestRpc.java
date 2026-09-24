package com.farzam.signer.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.web3j.utils.Numeric;

/**
 * A minimal JSON-RPC client for the tests, including the calls only Anvil has.
 *
 * <p>Separate from the production {@link com.farzam.signer.chain.EthereumRpc} on purpose. That class
 * is under test, so using it to arrange a test's preconditions would let one bug hide another —
 * a broken {@code balanceOf} would report the destination's balance as unchanged both before and
 * after, and the assertion would pass. This one is written against {@code java.net.http} and shares
 * no code with it.
 *
 * <p>It also reaches for {@code anvil_setBalance}, which is not part of Ethereum: it is how a test
 * funds a freshly-generated wallet without hard-coding one of Anvil's well-known development keys
 * into the repository, where the secret scanner would rightly object to sixty-four hex characters.
 */
public final class TestRpc {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;

    public TestRpc(String rpcUrl) {
        this.endpoint = URI.create(rpcUrl);
    }

    /**
     * Gives an address a balance out of thin air.
     *
     * @param address the account to fund
     * @param wei how much it should have afterwards
     */
    public void setBalance(String address, BigInteger wei) {
        call("anvil_setBalance", address, Numeric.encodeQuantity(wei));
    }

    /**
     * @param address the account
     * @return its balance in wei
     */
    public BigInteger balanceOf(String address) {
        return Numeric.decodeQuantity(call("eth_getBalance", address, "latest").asText());
    }

    /**
     * @param txHash a transaction hash
     * @return whether the chain has a receipt for it, and that receipt says it succeeded
     */
    public boolean minedSuccessfully(String txHash) {
        JsonNode receipt = call("eth_getTransactionReceipt", txHash);
        return !receipt.isNull() && "0x1".equals(receipt.path("status").asText());
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
