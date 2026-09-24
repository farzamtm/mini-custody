package com.farzam.custody.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The three JSON-RPC calls custody-api makes, and nothing else.
 *
 * <p>All three are reads. This service holds no key and cannot send a transaction; it asks the chain
 * what happened and writes the answer down.
 *
 * <p><b>Hand-rolled, and without web3j.</b> The signer carries {@code org.web3j:crypto} because it
 * does secp256k1 and RLP, where a mistake costs a key. Nothing here is cryptographic — it is three
 * HTTP calls and some hex — and putting an Ethereum crypto library on custody-api's classpath would
 * quietly weaken the claim that the signer is the only component that can sign. The cost is
 * {@link #quantity}, which is nine lines and has a test.
 *
 * <p><b>Requests and responses are handled as strings</b>, for the reason the signer's equivalent
 * gives: this build has Jackson 2 (through {@code common}) and Jackson 3 (through Spring Boot 4) on
 * the classpath, and which message converter a {@code RestClient} picks for a given type is a
 * question with a version-dependent answer. Reading the body as text and parsing it here makes the
 * question not arise.
 *
 * <p><b>Every quantity is a hex string.</b> JSON-RPC encodes numbers that way precisely because a
 * JSON number is a double in most parsers and a wei value does not fit in one — the same reason this
 * project's own API uses decimal strings.
 *
 * <p>This is the second JSON-RPC client in the repository and it is deliberately not shared. See
 * ADR 0011: the two have different call sets, different failure policies and different reasons to
 * exist, and the module that could hold a shared one is {@code common}, which must stay
 * framework-free and would need Spring's {@code RestClient}.
 */
@Component
public class EthereumRpc {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The block tag every read here uses: the latest mined block, not the pending one. */
    private static final String LATEST = "latest";

    /** A JSON-RPC quantity: {@code 0x} and at least one hex digit, no leading zeros required. */
    private static final Pattern QUANTITY = Pattern.compile("^0x[0-9a-fA-F]+$");

    private final RestClient http;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    EthereumRpc(ChainProperties chain) {
        var requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(chain.rpcTimeout());
        this.http = RestClient.builder().baseUrl(chain.rpcUrl().toString()).requestFactory(requests).build();
    }

    /**
     * The latest mined block.
     *
     * <p>Fetched once per batch rather than once per withdrawal, so every receipt in a tick is
     * counted against the same head. Otherwise two withdrawals mined in the same block could be
     * measured against different heights, and one could reach three confirmations a tick before the
     * other for no reason anybody could explain from the data.
     *
     * @return the block number
     */
    public long blockNumber() {
        return quantity(call("eth_blockNumber")).longValueExact();
    }

    /**
     * What the chain says about a transaction, if it says anything yet.
     *
     * <p>Empty means the node has no receipt, and that has three quite different causes it cannot
     * distinguish between: the transaction is still in the mempool, it was dropped, or it was mined
     * and then reorganised out. All three are handled the same way here — keep waiting — because the
     * remedy for all three is the signer resending the same signed bytes, which it does on its own
     * timer. What reconciliation adds is noticing when "keep waiting" has gone on too long.
     *
     * @param txHash the transaction to ask about
     * @return its receipt, or empty if the chain has none
     */
    public Optional<TransactionReceipt> receiptOf(String txHash) {
        JsonNode receipt = call("eth_getTransactionReceipt", txHash);
        if (receipt.isNull() || receipt.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(
                new TransactionReceipt(
                        quantity(receipt.path("blockNumber")).longValueExact(),
                        // "0x1" is success and "0x0" is a revert. Compared against success rather
                        // than against failure so that a node returning something unexpected reads
                        // as "not successful" — the safe direction, since the consequence of
                        // guessing wrong the other way is settling the ledger for money that never
                        // moved.
                        BigInteger.ONE.equals(quantity(receipt.path("status"))),
                        quantity(receipt.path("gasUsed")),
                        quantity(receipt.path("effectiveGasPrice"))));
    }

    /**
     * What an address holds, per the chain.
     *
     * <p>Used only by reconciliation, and only to report. Nothing settles on it.
     *
     * @param address the account, hex
     * @return its balance in wei
     */
    public BigInteger balanceOf(String address) {
        return quantity(call("eth_getBalance", address, LATEST));
    }

    /**
     * Reads a JSON-RPC quantity.
     *
     * <p>{@code BigInteger} rather than {@code long} even for a block number, and the conversion is
     * {@code longValueExact} rather than {@code longValue}: a node returning nonsense should throw
     * here rather than silently truncate into a block height that reads as plausible and is wrong by
     * 2^64.
     *
     * @throws RpcException if the value is absent or is not a hex quantity
     */
    private static BigInteger quantity(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw new RpcException("the node returned no value where a quantity was expected");
        }
        String text = node.asText();
        if (!QUANTITY.matcher(text).matches()) {
            throw new RpcException("the node returned a malformed quantity");
        }
        return new BigInteger(text.substring(2), 16);
    }

    /**
     * One call, with the error turned into an exception.
     *
     * @return the {@code result} member, which may legitimately be JSON null
     */
    private JsonNode call(String method, Object... params) {
        String request = write(
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        nextRequestId.getAndIncrement(),
                        "method",
                        method,
                        "params",
                        List.of(params)));

        JsonNode response;
        try {
            String body = http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            response = JSON.readTree(body == null ? "{}" : body);
        } catch (RestClientException unreachable) {
            throw new RpcException("could not reach the node for " + method, unreachable);
        } catch (JsonProcessingException malformed) {
            throw new RpcException("the node's response to " + method + " was not JSON", malformed);
        }

        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new RpcException(method + " failed: " + error.path("message").asText());
        }
        return response.path("result");
    }

    private static String write(Map<String, Object> request) {
        try {
            return JSON.writeValueAsString(request);
        } catch (JsonProcessingException impossible) {
            throw new RpcException("could not serialise a JSON-RPC request", impossible);
        }
    }
}
