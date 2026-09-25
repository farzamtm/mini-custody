package com.farzam.events;

import com.farzam.crypto.Ed25519;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Proof that a message came from the service that claims to have sent it.
 *
 * <p><b>What this is for.</b> The signer goes to some lengths not to believe custody-api: it
 * re-verifies every approval against its own registry before it will touch a private key, so an
 * attacker who owns custody-api completely still cannot make it sign. Until M7 that distrust ran one
 * way only. custody-api took whatever appeared on {@link Topics#SIGNER_RESULTS} at face value, and
 * one of the messages it accepted was "the signer refused, give the customer their money back" —
 * which releases a ledger hold. Anyone who could produce to the topic could therefore have a
 * customer's balance restored while the real signer was independently signing and broadcasting the
 * same withdrawal, and the money left the hot wallet while the books said it had not. ADR 0012 has
 * the full argument.
 *
 * <p>So the signer now signs the exact bytes it publishes and custody-api refuses anything it cannot
 * verify. The property is the mirror image of {@code SigningPolicy}'s: the key is deployment
 * configuration on both sides, so compromising one service does not yield the other's key.
 *
 * <p><b>The signature covers the message text, not a re-serialisation of it.</b> Kafka delivers a
 * record's value byte for byte, so the producer can sign exactly what it sends and the consumer can
 * verify exactly what it received, with no canonicalisation step in between that could disagree.
 * That is deliberately unlike {@link ApprovalStatement}, which has to be rebuilt from stored fields
 * because {@code jsonb} does not preserve the bytes that went into it — the reasoning is in
 * {@link EventJson}. Here nothing is stored between signing and verifying, so the simpler and
 * stricter option is available: the verifier checks the literal bytes it is about to parse, and a
 * single flipped character anywhere in the envelope fails the check.
 *
 * <p><b>Why a Kafka header rather than a field on the envelope.</b> A signature over a structure
 * cannot live inside that structure, so putting it on {@link EventEnvelope} would mean signing some
 * subset of the envelope's own fields and arguing about which — and every such argument is a place
 * for a field to be quietly left out of the signed set. A header sits beside the value, covers all
 * of it, and leaves the event contract that both services already parse completely unchanged.
 */
public final class EventSignature {

    /**
     * The Kafka header the signature travels in.
     *
     * <p>Lower case and hyphenated to match the convention for Kafka headers rather than the Java
     * one, because this string is wire format: it is read by whatever ends up consuming these topics
     * and not only by this codebase.
     */
    public static final String HEADER = "x-event-signature";

    private EventSignature() {}

    /**
     * Signs a message that is about to be published.
     *
     * @param key the publishing service's private key
     * @param message the exact record value that will be sent
     * @return the signature, base64, for the {@link #HEADER} header
     * @throws IllegalStateException if the key cannot sign
     */
    public static String sign(PrivateKey key, String message) {
        return Base64.getEncoder().encodeToString(Ed25519.sign(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Checks a message against the key the consumer was deployed with.
     *
     * <p>Every way this can fail returns false: a missing header, text that is not base64, the wrong
     * number of bytes, a good signature over different bytes, a signature by the wrong key. The
     * caller's response is the same in all of them — refuse the message — and a verifier that threw
     * for some of those and returned false for others would eventually be used without the catch.
     * That is the argument {@link Ed25519#verify} already makes; this only extends it to cover the
     * decoding.
     *
     * @param key the expected publisher's public key, from the consumer's own configuration
     * @param message the record value as it arrived
     * @param signatureBase64 the {@link #HEADER} header, or null if the message carried none
     * @return true only if this key signed exactly these bytes
     */
    public static boolean verify(PublicKey key, String message, String signatureBase64) {
        if (signatureBase64 == null) {
            return false;
        }

        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureBase64);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }

        return Ed25519.verify(key, message.getBytes(StandardCharsets.UTF_8), signature);
    }
}
