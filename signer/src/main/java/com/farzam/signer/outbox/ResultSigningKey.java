package com.farzam.signer.outbox;

import com.farzam.crypto.Ed25519;
import com.farzam.events.EventSignature;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key the signer authenticates its own results with.
 *
 * <p>Nothing about a withdrawal depends on this key: it does not sign transactions, it cannot move
 * funds, and losing it costs an attacker nothing on chain. What it buys is the other direction of
 * the trust model — custody-api releases a ledger hold when it is told the signer refused, so it has
 * to be able to tell a real refusal from a fabricated one. ADR 0012 has the argument;
 * {@link EventSignature} has the mechanism.
 *
 * <p><b>It is deliberately not the wallet key, and deliberately not the master key.</b> A separate
 * key means the thing that proves "the signer said this" is not the thing that proves "the signer
 * may spend this", so it can be rotated on its own schedule, and a copy of it leaking is an
 * authenticity problem rather than a custody one. It is also a different algorithm to the wallet's
 * secp256k1, which keeps the two impossible to confuse in configuration.
 *
 * <p><b>An absent or malformed key stops the service from starting.</b> This is the opposite of the
 * fail-closed default that {@code PolicyProperties} uses, and the asymmetry is on purpose. A signer
 * that refuses to sign is safe: withdrawals stall, nothing moves, somebody notices. A signer that
 * signs transactions but cannot authenticate the results it reports is not — it would broadcast on
 * chain and then have every report rejected by custody-api, stranding each withdrawal with the
 * client's funds held and the money already gone. The only safe moment to discover a missing key is
 * before the first withdrawal, so a bad one is a startup failure rather than a runtime one.
 *
 * <p>{@code final} because the constructor throws, and a constructor that throws on a subclassable
 * class is the finalizer attack: a subclass overriding {@code finalize} gets a partially built
 * instance handed to it after the exception. Nothing here needs to be subclassed, and Spring does
 * not proxy it — there is no {@code @Transactional} on it — so sealing the class costs nothing.
 */
@Component
public final class ResultSigningKey {

    private final PrivateKey key;

    ResultSigningKey(@Value("${signer.results.signing-key:}") String seedBase64) {
        if (seedBase64 == null || seedBase64.isBlank()) {
            throw new IllegalStateException(
                    "signer.results.signing-key is not set; the signer cannot authenticate the results it "
                            + "publishes, and custody-api would reject every one of them");
        }

        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(seedBase64.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("signer.results.signing-key is not valid base64", notBase64);
        }

        try {
            this.key = Ed25519.privateKeyFrom(seed);
        } catch (IllegalArgumentException rejected) {
            throw new IllegalStateException("signer.results.signing-key is not an Ed25519 private key", rejected);
        } finally {
            // The decoded seed has done its job. Zeroing it does not help against a heap dump taken
            // a moment earlier, and the key object itself still holds the secret — this is the same
            // modest hygiene MasterKey practises, for the same reason: the copies that are easy to
            // get rid of should be got rid of.
            Arrays.fill(seed, (byte) 0);
        }
    }

    /**
     * Signs one outbound message.
     *
     * @param message the exact record value about to be published
     * @return the signature, base64, for the {@link EventSignature#HEADER} header
     */
    public String sign(String message) {
        return EventSignature.sign(key, message);
    }
}
