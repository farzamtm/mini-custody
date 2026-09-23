package com.farzam.custody.withdrawal;

import java.io.Serial;

/**
 * A client sent an idempotency key it had already used, with a different request behind it.
 *
 * <p>Maps to {@code 409}. The alternative answers are both worse: creating a second withdrawal
 * would make the key meaningless, and returning the first one would tell a client that asked to send
 * 2 ETH to address B that it had succeeded, when what exists is a withdrawal of 1 ETH to address A.
 * There is no reading of the request that is safe to act on, so the server refuses and says so.
 *
 * <p>Neither the key nor the two requests appear in the message. The key is arbitrary client-chosen
 * text on its way into a log and an HTTP body, and the original request belongs to whoever made it —
 * an attacker who guesses a key should not be able to read back what it was used for.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyKeyReusedException() {
        super("this Idempotency-Key was already used for a different request");
    }
}
