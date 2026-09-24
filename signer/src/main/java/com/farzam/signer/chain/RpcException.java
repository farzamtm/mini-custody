package com.farzam.signer.chain;

import java.io.Serial;

/**
 * The node could not be reached, or refused the call.
 *
 * <p>Deliberately one type for both, because the signer's response is the same either way: do not
 * sign, let the message be retried, and if it keeps failing let it be dead-lettered so somebody
 * looks at the node. The distinction between "connection refused" and "execution reverted" matters
 * to whoever reads the message, not to the control flow.
 *
 * <p>The one case that is <em>not</em> an error is a resend of an already-known transaction, which
 * some nodes report as a failure. {@link EthereumRpc#sendRawTransaction} recognises it and returns
 * normally, because re-sending identical signed bytes is how the retry job is supposed to work.
 */
public class RpcException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what the node said, or what went wrong reaching it
     */
    public RpcException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause the transport's own complaint
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
