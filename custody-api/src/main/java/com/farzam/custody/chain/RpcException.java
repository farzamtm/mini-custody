package com.farzam.custody.chain;

import java.io.Serial;

/**
 * The node could not be reached, or said something this service cannot read.
 *
 * <p>Unchecked, and it is meant to escape. The confirmation watcher runs on a timer, so the recovery
 * for "the node is down" is to do nothing and come back in two seconds — which is what letting this
 * propagate out of a scheduled method achieves, once the transaction has rolled back and the row
 * locks are gone. A caught-and-logged version would have to decide what a half-processed batch
 * means, and the answer would be worse than not starting one.
 *
 * <p>The one thing it must never do is reach the ledger. A withdrawal is settled because the chain
 * said so; a node that cannot be asked has said nothing, and "no answer" must not read as "no
 * receipt".
 */
public class RpcException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what went wrong
     */
    public RpcException(String message) {
        super(message);
    }

    /**
     * @param message what went wrong
     * @param cause the client's or the parser's own complaint
     */
    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
