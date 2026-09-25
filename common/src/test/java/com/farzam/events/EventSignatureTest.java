package com.farzam.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.crypto.Ed25519;
import com.fasterxml.jackson.databind.node.NullNode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What custody-api relies on to tell a real signer result from one somebody else produced. */
class EventSignatureTest {

    private static final String MESSAGE = "{\"eventId\":\"a\",\"eventType\":\"withdrawal.broadcast.v1\"}";

    @Test
    void aMessageSignedByTheSignerVerifiesAgainstItsPublicKey() {
        KeyPair signer = generate();

        String signature = EventSignature.sign(signer.getPrivate(), MESSAGE);

        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE, signature)).isTrue();
    }

    /**
     * The attack this whole mechanism exists to stop, reduced to one assertion: a well-formed message
     * nobody signed is refused.
     */
    @Test
    void aMessageWithNoSignatureAtAllIsRefused() {
        assertThat(EventSignature.verify(generate().getPublic(), MESSAGE, null)).isFalse();
    }

    @Test
    void aMessageSignedByAnybodyElseIsRefused() {
        KeyPair outsider = generate();

        String signature = EventSignature.sign(outsider.getPrivate(), MESSAGE);

        assertThat(EventSignature.verify(generate().getPublic(), MESSAGE, signature)).isFalse();
    }

    /**
     * The signature covers the whole message, so editing any part of it invalidates the header —
     * including the parts an attacker would most want to change.
     */
    @Test
    void aMessageEditedAfterSigningIsRefused() {
        KeyPair signer = generate();
        String signature = EventSignature.sign(signer.getPrivate(), MESSAGE);

        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE.replace('a', 'b'), signature)).isFalse();
        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE + " ", signature)).isFalse();
        assertThat(EventSignature.verify(signer.getPublic(), "", signature)).isFalse();
    }

    /**
     * Junk in the header is false, not an exception.
     *
     * <p>The header is entirely attacker-controlled on the one path whose job is handling hostile
     * input. A decoder that threw on bad base64 would turn "refuse this message" into an error two
     * layers below the listener, where the non-retryable classification does not apply and the
     * message would be retried three times before being set aside.
     */
    @Test
    void aHeaderThatIsNotEvenBase64IsRefusedRatherThanThrowing() {
        KeyPair signer = generate();

        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE, "not base64 !!!")).isFalse();
        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE, "")).isFalse();
        assertThat(EventSignature.verify(signer.getPublic(), MESSAGE, "c2hvcnQ=")).isFalse();
    }

    /**
     * Signing the serialised envelope covers every field of it, which is the reason the signature is
     * a header over the message text rather than a field inside the envelope.
     *
     * <p>Each of these is an edit an attacker would want: a fresh {@code eventId} to slip past the
     * duplicate check, a different {@code eventType} to turn a broadcast into a refusal, a different
     * {@code aggregateId} to point the result at somebody else's withdrawal.
     */
    @Test
    void everyFieldOfTheEnvelopeIsCoveredBySignatureOverItsSerialisedForm() {
        KeyPair signer = generate();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-09-25T10:15:30Z");
        EventEnvelope original = new EventEnvelope(
                eventId,
                EventType.WITHDRAWAL_BROADCAST,
                occurredAt,
                aggregateId,
                NullNode.instance);
        String signature = EventSignature.sign(signer.getPrivate(), EventJson.write(original));

        assertThat(EventSignature.verify(signer.getPublic(), EventJson.write(original), signature)).isTrue();

        assertThat(
                tamper(
                        signer,
                        signature,
                        new EventEnvelope(
                                UUID.randomUUID(),
                                EventType.WITHDRAWAL_BROADCAST,
                                occurredAt,
                                aggregateId,
                                NullNode.instance)))
                .as("a fresh event id")
                .isFalse();
        assertThat(
                tamper(
                        signer,
                        signature,
                        new EventEnvelope(
                                eventId,
                                EventType.WITHDRAWAL_SIGNING_FAILED,
                                occurredAt,
                                aggregateId,
                                NullNode.instance)))
                .as("a different event type")
                .isFalse();
        assertThat(
                tamper(
                        signer,
                        signature,
                        new EventEnvelope(
                                eventId,
                                EventType.WITHDRAWAL_BROADCAST,
                                occurredAt,
                                UUID.randomUUID(),
                                NullNode.instance)))
                .as("a different withdrawal")
                .isFalse();
    }

    private static boolean tamper(KeyPair signer, String signature, EventEnvelope edited) {
        return EventSignature.verify(signer.getPublic(), EventJson.write(edited), signature);
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
