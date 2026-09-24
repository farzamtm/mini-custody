package com.farzam.custody.approval;

import java.io.Serial;

/**
 * The bytes offered as an Ed25519 public key are not one.
 *
 * <p>A {@code 400}, like {@link com.farzam.custody.chain.MalformedAddressException} and for the same
 * reason: the request is not merely one that cannot be carried out, it is not well formed. The
 * contract's pattern catches the common case at the edge; this catches the rest, because a service
 * that is only correct while the schema says what it says today is not correct.
 */
public class MalformedPublicKeyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong with it
     * @param cause the decoder's or the key factory's own complaint
     */
    public MalformedPublicKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
