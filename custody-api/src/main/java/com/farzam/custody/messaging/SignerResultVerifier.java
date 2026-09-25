package com.farzam.custody.messaging;

import com.farzam.events.EventSignature;
import java.security.PublicKey;
import org.springframework.stereotype.Component;

/**
 * Refuses any signer result this service cannot prove the signer sent.
 *
 * <p>The mirror image of the signer's {@code SigningPolicy}: both check a signature against a key
 * their own deployment gave them, and neither will take the other's word for anything. See
 * {@link SignerResultsProperties} for where the key comes from and ADR 0012 for why this exists.
 *
 * <p>The key is resolved once here rather than on every message, so a malformed one is a startup
 * failure. A per-message parse would turn one configuration mistake into a steady stream of
 * authentication failures that read exactly like an attack.
 */
@Component
public final class SignerResultVerifier {

    /** Null when no key is configured, which means every message is refused. */
    private final PublicKey signerKey;

    SignerResultVerifier(SignerResultsProperties properties) {
        this.signerKey = properties.signerKey().orElse(null);
    }

    /**
     * Checks one message, or throws.
     *
     * <p>Throwing rather than returning a boolean, because there is exactly one correct response to
     * a message that does not verify and making it the caller's decision invites a call site that
     * logs and carries on.
     *
     * @param message the record value exactly as it arrived
     * @param signatureBase64 the {@link EventSignature#HEADER} header, or null if there was none
     * @throws UnauthenticEventException if no key is configured, or the signature does not verify
     */
    public void require(String message, String signatureBase64) {
        if (signerKey == null) {
            throw new UnauthenticEventException(
                    "custody.signer-results.public-key is not configured, so no signer result can be "
                            + "authenticated and none will be applied");
        }

        if (!EventSignature.verify(signerKey, message, signatureBase64)) {
            // The message itself is not quoted. It is attacker-supplied text on the one path whose
            // whole purpose is handling attacker-supplied text, and the dead-letter topic already
            // has the bytes for whoever needs them.
            throw new UnauthenticEventException("a signer result did not carry a valid signature");
        }
    }
}
