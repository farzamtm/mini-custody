package com.farzam.custody.chain;

import java.io.Serial;

/**
 * A string that was supposed to be an Ethereum address is not one.
 *
 * <p>Maps to {@code 400}, not {@code 422}: the request is not merely impossible to carry out, it is
 * not a well-formed request at all.
 *
 * <p>The message deliberately does not echo the offending value back. It is attacker-controlled
 * text of unbounded shape, and the only two places it would land are an HTTP response body and a
 * log line — the two places where reflecting unvalidated input causes trouble.
 */
public class MalformedAddressException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MalformedAddressException(String address) {
        super(
                "not an Ethereum address: expected 0x followed by 40 hex characters, got %d characters"
                        .formatted(address == null ? 0 : address.length()));
    }
}
