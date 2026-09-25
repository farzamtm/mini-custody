package com.farzam.signer.chain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What this client does when the node says something that is not what a node says.
 *
 * <p>The one place in this module that fakes a node, for the reason custody-api's equivalent gives:
 * every other test runs against Anvil so the client is checked against a real implementation, but a
 * well-behaved node never produces malformed input and these branches only run for malformed input.
 * They are not hypothetical — an HTTP proxy returning an error page, a gateway returning its own
 * JSON on a rate limit, a load balancer truncating a response.
 *
 * <p><b>Why this file exists at all, when custody-api already had one.</b> The two services keep
 * separate JSON-RPC clients on purpose, and nine lines of quantity parsing is the acknowledged cost.
 * What was not decided is that the two should disagree about what a bad answer looks like: this copy
 * delegated to {@code Numeric.decodeQuantity}, which validates the {@code 0x} prefix and then hands
 * the rest to {@code BigInteger}, so {@code "0xzz"} escaped as an unlabelled
 * {@code NumberFormatException} while custody-api reported the identical reply as
 * {@code RpcException}. An operator reading logs saw two failure signatures for one cause, and only
 * one of them named the node.
 */
class EthereumRpcMalformedResponseTest {

    private static final String ADDRESS = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>();
    private EthereumRpc rpc;

    @BeforeEach
    void startAServerThatSaysWhateverTheTestWants() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        rpc = new EthereumRpc(
                new ChainProperties(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        31337,
                        null,
                        null,
                        Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * Every shape of non-quantity is the same labelled failure.
     *
     * <p>{@code "0xzz"} is the case that used to get through the decoder's own validation and come
     * back out as {@code NumberFormatException}. The others were already handled; they are here so
     * that a future edit cannot fix one and regress another.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0xzz", "twelve", "0x", "", "12"})
    void aQuantityThatIsNotHexIsAnRpcException(String quantity) {
        response.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + quantity + "\"}");

        assertThatThrownBy(() -> rpc.transactionCount(ADDRESS)).isInstanceOf(RpcException.class)
                .hasMessageContaining("malformed");
    }

    /**
     * Absent is not zero.
     *
     * <p>A nonce of zero is a real value — a wallet that has never sent anything — so defaulting to
     * it would let "the node said nothing" seed {@code chain_nonces} with a number the chain does
     * not agree with, and every transaction signed against it would be rejected.
     */
    @Test
    void aMissingResultIsAnRpcExceptionAndNotZero() {
        response.set("{\"jsonrpc\":\"2.0\",\"id\":1}");

        assertThatThrownBy(() -> rpc.transactionCount(ADDRESS)).isInstanceOf(RpcException.class)
                .hasMessageContaining("no value");
    }

    @Test
    void aBodyThatIsNotJsonIsAnRpcException() {
        response.set("<html>502 Bad Gateway</html>");

        assertThatThrownBy(() -> rpc.transactionCount(ADDRESS)).isInstanceOf(RpcException.class);
    }

    @Test
    void aJsonRpcErrorIsAnRpcException() {
        response.set("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"rate limited\"}}");

        assertThatThrownBy(() -> rpc.transactionCount(ADDRESS)).isInstanceOf(RpcException.class)
                .hasMessageContaining("rate limited");
    }
}
