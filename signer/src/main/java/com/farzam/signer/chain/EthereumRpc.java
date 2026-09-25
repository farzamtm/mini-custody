package com.farzam.signer.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.web3j.exceptions.MessageDecodingException;
import org.web3j.utils.Numeric;

/**
 * The four JSON-RPC calls this service makes, and nothing else.
 *
 * <p><b>Hand-rolled rather than web3j's {@code Web3j}.</b> That client is a fine piece of work and it
 * brings okhttp, RxJava 2, a WebSocket stack, a Unix-socket JNR binding and the AWS KMS SDK with it.
 * Four calls do not justify that on a service whose argument for existing is that it is small, and
 * whose dependency tree is a merge gate. What is <em>not</em> hand-rolled is anything cryptographic:
 * signing and RLP encoding stay in {@code org.web3j:crypto}, where a mistake would cost a key rather
 * than a stack trace.
 *
 * <p><b>Requests and responses are handled as strings.</b> This module has both Jackson 2 (through
 * {@code common}'s event contract) and Jackson 3 (through Spring Boot 4) on its classpath, so which
 * message converter a {@code RestClient} would pick for a given type is a question with a
 * version-dependent answer. Reading the body as text and parsing it here makes that question not
 * arise. The parsing is six lines.
 *
 * <p><b>Every quantity is a hex string.</b> JSON-RPC encodes numbers as {@code "0x1a"} precisely
 * because a JSON number is a double in most parsers and a wei value does not fit in one — the same
 * reason this project's own API uses decimal strings. {@link Numeric#decodeQuantity} is web3j's
 * reader for the format.
 */
@Component
public class EthereumRpc {

    private static final Logger LOG = LoggerFactory.getLogger(EthereumRpc.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The block tag every read here uses: the latest mined block, not the pending one. */
    private static final String LATEST = "latest";

    /** An even number of hex digits, optionally prefixed. Guards {@link #hexBytes}. */
    private static final Pattern HEX = Pattern.compile("^(0x)?([0-9a-fA-F]{2})+$");

    /**
     * What a node says when it already has these exact bytes.
     *
     * <p>Not an error, and the whole reason resending is safe. The broadcast retry job exists to
     * push the same signed transaction at the network until it sticks, and a node that has already
     * seen it saying so is the retry succeeding, not failing. Matched on the message text because
     * JSON-RPC error codes for this are not standardised — geth, Anvil and Erigon each pick their
     * own — which is unpleasant and is still better than treating a successful resend as a failure
     * and retrying for ever.
     */
    private static final Pattern ALREADY_HAVE_IT = Pattern.compile(
            "already known|already imported|known transaction|transaction already exists",
            Pattern.CASE_INSENSITIVE);

    private final RestClient http;
    private final BigInteger fallbackPriorityFee;
    private final AtomicLong nextRequestId = new AtomicLong(1);

    EthereumRpc(ChainProperties chain) {
        var requests = new JdkClientHttpRequestFactory();
        requests.setReadTimeout(chain.rpcTimeout());
        this.http = RestClient.builder().baseUrl(chain.rpcUrl().toString()).requestFactory(requests).build();
        this.fallbackPriorityFee = chain.fallbackPriorityFeeWei();
    }

    /**
     * How many transactions this address has already sent, per the chain.
     *
     * <p>{@code latest} rather than {@code pending}: this is only used to seed {@code chain_nonces}
     * the first time, and the seed should be a fact about mined history rather than about whatever
     * happens to be in this node's mempool. After that the counter in Postgres is authoritative,
     * because it is the only one that can be reserved transactionally.
     *
     * @param address the account, hex
     * @return its transaction count, which is also the next nonce to use
     */
    public BigInteger transactionCount(String address) {
        return quantity(call("eth_getTransactionCount", address, LATEST));
    }

    /**
     * @param address the account, hex
     * @return its balance in wei
     */
    public BigInteger balanceOf(String address) {
        return quantity(call("eth_getBalance", address, LATEST));
    }

    /**
     * Whether the chain has a receipt for a transaction.
     *
     * <p>Used for one question, and it is a question with real money behind it: a node that refuses
     * a resend with "nonce too low" is saying that the nonce has been used, and that has two very
     * different causes. Either this transaction was mined — in which case the resend was redundant
     * and everything is fine — or something else spent the nonce, in which case this transaction can
     * never be mined and no amount of retrying will change that. The error message is identical. The
     * receipt is what tells them apart.
     *
     * <p>A boolean is the whole of what this service needs. custody-api reads the same receipt
     * properly — block number, status, gas — to count confirmations and settle the ledger, and it
     * does so through a client of its own rather than this one. See ADR 0011 for why the two are
     * not shared: they have different call sets and, more to the point, different failure policies.
     * This one treats "already known" as success, and nothing on the settlement side should.
     *
     * @param txHash the transaction to look for
     * @return whether the chain knows it
     */
    public boolean hasReceipt(String txHash) {
        JsonNode receipt = call("eth_getTransactionReceipt", txHash);
        return !receipt.isNull() && !receipt.isMissingNode();
    }

    /**
     * What to offer for gas, in EIP-1559's two numbers.
     *
     * <p>{@code maxFeePerGas} is twice the current base fee plus the tip. The doubling is the
     * standard allowance for the base fee rising while the transaction waits: it can go up by 12.5%
     * per block, so a 2× headroom survives about six consecutive full blocks. Offering exactly the
     * current base fee produces a transaction that is valid now and unmineable one block later, and
     * unspent headroom costs nothing — the protocol refunds the difference between the base fee and
     * {@code maxFeePerGas}, so this is a ceiling rather than a price.
     *
     * @return the two fee fields for the next transaction
     */
    public Fees currentFees() {
        JsonNode block = call("eth_getBlockByNumber", LATEST, false);
        BigInteger baseFee = quantity(block.path("baseFeePerGas"));
        BigInteger tip = suggestedPriorityFee();
        return new Fees(tip, baseFee.multiply(BigInteger.TWO).add(tip));
    }

    /**
     * Puts signed bytes on the network.
     *
     * <p>Idempotent by construction, which is the property the whole broadcast design leans on: the
     * same signed transaction sent twice is one transaction, because its hash is a hash of its
     * bytes. Sending it again after a timeout is therefore always safe, and re-<em>signing</em> it
     * with a fresh nonce never is — two valid transactions paying the same person could both be
     * mined.
     *
     * @param rawTransaction the signed transaction, {@code 0x}-prefixed hex
     * @param expectedHash the hash computed at signing time, for the check below
     * @throws RpcException if the node refused it for any reason other than already having it
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "expectedHash is hex this service computed from keccak-256 of bytes it "
                    + "signed. It cannot contain a newline, and it does not come off the wire.")
    public void sendRawTransaction(String rawTransaction, String expectedHash) {
        JsonNode response = invoke("eth_sendRawTransaction", rawTransaction);

        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = error.path("message").asText("");
            if (alreadyHaveIt(message)) {
                LOG.debug("the node already has {}; the resend was a no-op", expectedHash);
                return;
            }
            throw new RpcException("eth_sendRawTransaction was refused: " + message);
        }

        // A disagreement here would mean this service computed a transaction hash that is not the
        // hash of the transaction it sent — so signing_log, the WithdrawalBroadcast event and every
        // receipt lookup built on them would all be about a transaction that does not exist. It
        // cannot happen while the hash is keccak-256 of the same bytes, which is exactly why it is
        // worth one comparison to find out if it ever does.
        // Compared as the bytes they stand for rather than with equalsIgnoreCase, for the reason
        // given on alreadyHaveIt: case folding before a security decision is locale-dependent, and
        // two hex strings have a byte comparison available that sidesteps the question entirely.
        String reported = response.path("result").asText("");
        if (!Arrays.equals(hexBytes(reported), hexBytes(expectedHash))) {
            throw new RpcException("the node hashed the transaction as " + reported + ", not " + expectedHash);
        }
    }

    /**
     * The node's own view of a reasonable tip.
     *
     * <p>Falling back to a configured floor rather than failing, because a node that does not
     * implement {@code eth_maxPriorityFeePerGas} is a node this service can still use — the method
     * is a convenience over {@code eth_feeHistory}, not part of the protocol.
     */
    private BigInteger suggestedPriorityFee() {
        try {
            return quantity(call("eth_maxPriorityFeePerGas"));
        } catch (RpcException unsupported) {
            LOG.debug("the node would not suggest a priority fee; using the configured floor", unsupported);
            return fallbackPriorityFee;
        }
    }

    /**
     * A case-insensitive pattern rather than {@code toLowerCase} then {@code contains}.
     *
     * <p>find-sec-bugs objected to the original and was right to. Case folding is locale-dependent
     * and, for some scripts, not reversible — the Turkish dotless i is the usual example — so
     * lower-casing a string and then making a security decision about it is a class of bug with a
     * name. This decision qualifies: it is where the service decides that a refusal by the node was
     * harmless. A pattern with {@link Pattern#CASE_INSENSITIVE} asks the question directly instead of
     * transforming the input first.
     */
    private static boolean alreadyHaveIt(String message) {
        return ALREADY_HAVE_IT.matcher(message).find();
    }

    /**
     * Hex to bytes, tolerating whatever the node sent.
     *
     * @return the bytes, or an empty array for anything that is not hex — which then compares equal
     *     to nothing, and so reads as a mismatch rather than as a match
     */
    private static byte[] hexBytes(String hex) {
        return HEX.matcher(hex).matches() ? Numeric.hexStringToByteArray(hex) : new byte[0];
    }

    private static BigInteger quantity(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            throw new RpcException("the node returned no value where a quantity was expected");
        }
        try {
            return Numeric.decodeQuantity(node.asText());
        } catch (MessageDecodingException malformed) {
            throw new RpcException("the node returned a malformed quantity", malformed);
        }
    }

    /**
     * One call, with the error turned into an exception.
     *
     * @return the {@code result} member
     */
    private JsonNode call(String method, Object... params) {
        JsonNode response = invoke(method, params);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new RpcException(method + " failed: " + error.path("message").asText());
        }
        return response.path("result");
    }

    /**
     * One call, with the error left in place for a caller that has an opinion about it.
     *
     * @return the whole JSON-RPC response object
     */
    private JsonNode invoke(String method, Object... params) {
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
        try {
            String body = http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            return JSON.readTree(body == null ? "{}" : body);
        } catch (RestClientException unreachable) {
            throw new RpcException("could not reach the node for " + method, unreachable);
        } catch (JsonProcessingException malformed) {
            throw new RpcException("the node's response to " + method + " was not JSON", malformed);
        }
    }

    private static String write(Map<String, Object> request) {
        try {
            return JSON.writeValueAsString(request);
        } catch (JsonProcessingException impossible) {
            throw new RpcException("could not serialise a JSON-RPC request", impossible);
        }
    }
}
