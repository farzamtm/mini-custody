package com.farzam.signer.crypto;

import java.io.Serial;

/**
 * A stored wallet key did not decrypt.
 *
 * <p>With AES-GCM there is no such thing as decrypting to the wrong answer, so every instance of
 * this means one of a short list: the ciphertext, IV or wrapped data key has been altered, the row
 * has been copied under a different address, or the signer is running with a different master key
 * than the one that sealed it. The first two are tampering and the third is a deployment mistake,
 * and all three should stop the process of signing rather than be retried.
 *
 * <p>Unchecked, because no caller can do anything locally about any of those. It propagates out of
 * the listener, the message is retried three times and dead-lettered, and somebody looks at it —
 * which is the correct handling for "the key store is not in the state this service was built
 * against".
 */
public class KeyUnsealingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what failed, in terms that do not narrow down why
     */
    public KeyUnsealingException(String message) {
        super(message);
    }

    /**
     * @param message what failed
     * @param cause the provider's complaint
     */
    public KeyUnsealingException(String message, Throwable cause) {
        super(message, cause);
    }
}
