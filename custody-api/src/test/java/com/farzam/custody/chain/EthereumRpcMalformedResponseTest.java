package com.farzam.custody.chain;

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

/**
 * What happens when the node says something that is not what a node says.
 *
 * <p><b>The one place in this module that fakes a node, and the reason is that a real one cannot be
 * made to do this.</b> Everywhere else the tests run against Anvil, precisely so that the client is
 * checked against a real implementation rather than against the test author's memory of one — see
 * {@link com.farzam.custody.support.AnvilContainer}. But the branches below only execute for
 * malformed input, and a well-behaved node never produces malformed input. They are not
 * hypothetical: an HTTP proxy returning an error page, a load balancer truncating a response, a
 * provider's gateway returning its own JSON on a rate limit. All of them reach this code as bytes
 * that are not a receipt.
 *
 * <p>What matters is the direction of the failure. Every one of these must throw, because the
 * alternative is a watcher that reads a garbled response as "no receipt" or "zero confirmations" and
 * then acts on it. There is no safe way to guess what the chain meant.
 */
class EthereumRpcMalformedResponseTest {

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
                        3,
                        "",
                        null,
                        0,
                        Duration.ofSeconds(2),
                        null));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aBodyThatIsNotJsonIsAnRpcException() {
        response.set("<html>502 Bad Gateway</html>");

        assertThatThrownBy(rpc::blockNumber).isInstanceOf(RpcException.class).hasMessageContaining("not JSON");
    }

    @Test
    void aJsonRpcErrorIsAnRpcException() {
        response.set("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"rate limited"}}
                """);

        assertThatThrownBy(rpc::blockNumber).isInstanceOf(RpcException.class).hasMessageContaining("rate limited");
    }

    /**
     * A response with no {@code result} at all.
     *
     * <p>Absent is not zero. A block height of zero is a real value — it is the genesis block — so
     * defaulting to it would make "the node said nothing" indistinguishable from "the chain is
     * empty", and every receipt would read as having an enormous number of confirmations.
     */
    @Test
    void aMissingResultIsAnRpcExceptionAndNotZero() {
        response.set("""
                {"jsonrpc":"2.0","id":1}
                """);

        assertThatThrownBy(rpc::blockNumber).isInstanceOf(RpcException.class).hasMessageContaining("no value");
    }

    @Test
    void aQuantityThatIsNotHexIsAnRpcException() {
        response.set("""
                {"jsonrpc":"2.0","id":1,"result":"twelve"}
                """);

        assertThatThrownBy(rpc::blockNumber).isInstanceOf(RpcException.class).hasMessageContaining("malformed");
    }

    /**
     * A receipt missing the fields the ledger depends on.
     *
     * <p>It has a {@code blockNumber} and a {@code status}, so a client that only checked for the
     * receipt's existence would happily settle — with a fee of zero, or with whatever a null read as.
     * The fee is money out of {@code BANK_OPERATING}, so the absence has to be loud.
     */
    @Test
    void aReceiptWithoutItsGasFieldsIsAnRpcException() {
        response.set("""
                {"jsonrpc":"2.0","id":1,"result":{"blockNumber":"0x10","status":"0x1"}}
                """);

        assertThatThrownBy(() -> rpc.receiptOf("0x" + "deadbeef".repeat(8))).isInstanceOf(RpcException.class);
    }
}
