package com.farzam.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.farzam.events.ApprovalStatement;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Raw-key packing, unpacking and verification, against keys the JDK generated. */
class Ed25519Test {

    private static final byte[] MESSAGE = "the statement an approver signs".getBytes(StandardCharsets.UTF_8);

    /**
     * Repeated, and that is not padding. The packed form carries the sign of x in the top bit of the
     * last byte and y in the remaining 255 — so roughly half of all keys have that bit set, and an
     * implementation that ignored it would pass a single-shot test about half the time. Sixteen runs
     * make that a one-in-sixty-five-thousand escape.
     */
    @RepeatedTest(16)
    void aRawPublicKeyUnpacksToTheKeyItWasPackedFrom() {
        KeyPair pair = generate();

        PublicKey unpacked = Ed25519.publicKeyFrom(Ed25519.rawPublicKey(pair.getPublic()));

        assertThat(unpacked).isEqualTo(pair.getPublic());
    }

    /**
     * The packed form is fixed-width, whatever the number in it happens to be.
     *
     * <p>y is a {@code BigInteger}, and {@code toByteArray} drops leading zeros — so a key whose y is
     * numerically small packs from fewer than 32 bytes, and an implementation that copied from the
     * left would silently shift every byte. The assertion is on the length because that is the
     * property the wire format depends on.
     */
    @RepeatedTest(16)
    void aPackedKeyIsAlwaysThirtyTwoBytes() {
        assertThat(Ed25519.rawPublicKey(generate().getPublic())).hasSize(32);
    }

    @Test
    void aGenuineSignatureVerifies() {
        KeyPair pair = generate();

        assertThat(Ed25519.verify(pair.getPublic(), MESSAGE, sign(pair, MESSAGE))).isTrue();
    }

    @Test
    void aSignatureOverDifferentBytesDoesNot() {
        KeyPair pair = generate();

        assertThat(
                Ed25519.verify(
                        pair.getPublic(),
                        "something else".getBytes(StandardCharsets.UTF_8),
                        sign(pair, MESSAGE)))
                .isFalse();
    }

    @Test
    void anotherKeysSignatureDoesNot() {
        KeyPair signer = generate();

        assertThat(Ed25519.verify(generate().getPublic(), MESSAGE, sign(signer, MESSAGE))).isFalse();
    }

    /**
     * The property the whole approval scheme rests on, stated once here so neither service has to
     * assume it: a signature over an {@link ApprovalStatement} stops verifying the moment any field
     * of that statement changes. custody-api signs the statement it recorded, the signer verifies the
     * one it rebuilds from the event, and this is why a tampered destination cannot survive the trip.
     */
    @Test
    void aSignedStatementDoesNotVerifyAgainstAnAmendedOne() {
        KeyPair pair = generate();
        UUID withdrawalId = UUID.randomUUID();
        String destination = "0x" + "a".repeat(40);
        ApprovalStatement signed = new ApprovalStatement(withdrawalId, destination, BigInteger.TEN);
        byte[] signature = sign(pair, signed.canonicalBytes());

        assertThat(Ed25519.verify(pair.getPublic(), signed.canonicalBytes(), signature)).isTrue();

        ApprovalStatement elsewhere = new ApprovalStatement(withdrawalId, "0x" + "b".repeat(40), BigInteger.TEN);
        ApprovalStatement larger = new ApprovalStatement(withdrawalId, destination, BigInteger.valueOf(11));

        assertThat(Ed25519.verify(pair.getPublic(), elsewhere.canonicalBytes(), signature)).isFalse();
        assertThat(Ed25519.verify(pair.getPublic(), larger.canonicalBytes(), signature)).isFalse();
    }

    /**
     * Malformed input returns false rather than throwing. Every caller's response to an invalid
     * signature is the same — refuse — and a verifier with two failure modes is one that will
     * eventually be called without the catch.
     */
    @Test
    void nonsenseInsteadOfASignatureIsFalseRatherThanAnException() {
        KeyPair pair = generate();

        assertThat(Ed25519.verify(pair.getPublic(), MESSAGE, new byte[64])).isFalse();
        assertThat(Ed25519.verify(pair.getPublic(), MESSAGE, new byte[0])).isFalse();
        assertThat(Ed25519.verify(pair.getPublic(), MESSAGE, "short".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    /**
     * A key, on the other hand, is configuration: a malformed one is a deployment that should not
     * start, not a signature that should quietly fail.
     */
    @Test
    void aKeyOfTheWrongLengthIsRejectedLoudly() {
        assertThatIllegalArgumentException().isThrownBy(() -> Ed25519.publicKeyFrom(new byte[31]))
                .withMessageContaining("32 bytes, got 31");
    }

    /**
     * Thirty-two bytes of the right length that are not a point on the curve.
     *
     * <p>The JDK accepts them. {@code KeyFactory} does not check that y is on the curve — it builds
     * the key and leaves the question to verification time, which is a reasonable choice and not the
     * one this code assumed. The consequence is worth pinning down rather than wishing away: a
     * nonsense public key in {@code signer.policy.trusted-approvers}, or in an {@code approvers} row,
     * passes the parse, and what happens instead is that every signature attributed to that approver
     * fails to verify. Both services then refuse. Fail-closed, but diagnosed at the first withdrawal
     * rather than at boot.
     */
    @Test
    void bytesThatAreNotACurvePointAreAcceptedAsAKeyAndThenVerifyNothing() {
        byte[] notAPoint = new byte[32];
        Arrays.fill(notAPoint, (byte) 0xFF);

        PublicKey accepted = Ed25519.publicKeyFrom(notAPoint);

        assertThat(Ed25519.verify(accepted, MESSAGE, new byte[64])).isFalse();
    }

    /**
     * The round trip M7 depends on: a key loaded from a 32-byte seed signs something the matching
     * public key verifies.
     *
     * <p>Repeated for the reason the packing tests are. The seed is expanded into a scalar before it
     * is used, and an implementation that mixed up the seed with the expanded form would agree with
     * itself and disagree with every other Ed25519 library — which is the kind of bug that only
     * shows up against a counterparty.
     */
    @RepeatedTest(16)
    void aKeyLoadedFromASeedSignsWhatItsPublicKeyVerifies() {
        KeyPair pair = generate();
        byte[] seed = seedOf(pair);

        PrivateKey loaded = Ed25519.privateKeyFrom(seed);

        assertThat(loaded).isEqualTo(pair.getPrivate());
        assertThat(Ed25519.verify(pair.getPublic(), MESSAGE, Ed25519.sign(loaded, MESSAGE))).isTrue();
    }

    @Test
    void aSeedOfTheWrongLengthIsRejectedLoudly() {
        assertThatIllegalArgumentException().isThrownBy(() -> Ed25519.privateKeyFrom(new byte[31]))
                .withMessageContaining("32 bytes, got 31");
    }

    /**
     * {@link Ed25519#privateKeyFrom} copies the seed rather than keeping the caller's array.
     *
     * <p>{@code ResultSigningKey} zeroes its buffer as soon as the key is built, so a key that held
     * a reference to it would sign with thirty-two zero bytes from then on — and would do it
     * silently, producing signatures that are perfectly well-formed and verify against nothing.
     */
    @Test
    void zeroingTheSeedAfterwardsDoesNotChangeWhatTheKeySigns() {
        byte[] seed = seedOf(generate());
        PrivateKey key = Ed25519.privateKeyFrom(seed);
        byte[] before = Ed25519.sign(key, MESSAGE);

        Arrays.fill(seed, (byte) 0);

        assertThat(Ed25519.sign(key, MESSAGE)).isEqualTo(before);
    }

    /**
     * A key of the wrong kind is a misconfiguration, so signing with one throws rather than
     * returning something.
     *
     * <p>Reachable in a way the other {@code GeneralSecurityException} branches in this class are
     * not: {@code initSign} rejects a key that is not Ed25519 outright. It is the branch worth
     * proving, because the alternative behaviour — a signer that returned null or empty bytes — would
     * publish an unsigned message and have it refused at the consumer, which is the exact failure the
     * signature is there to prevent.
     */
    @Test
    void signingWithAKeyThatIsNotEd25519IsAnIllegalState() {
        KeyPair wrongCurve = generateEc();

        assertThatIllegalStateException().isThrownBy(() -> Ed25519.sign(wrongCurve.getPrivate(), MESSAGE))
                .withMessageContaining("could not sign");
    }

    /** Signatures are deterministic in Ed25519 — no RNG, so no way for a reused nonce to leak a key. */
    @Test
    void signingTheSameBytesTwiceGivesTheSameSignature() {
        KeyPair pair = generate();

        assertThat(Ed25519.sign(pair.getPrivate(), MESSAGE)).isEqualTo(Ed25519.sign(pair.getPrivate(), MESSAGE));
    }

    /** The raw 32 bytes behind a generated private key — what a deployment would configure. */
    private static byte[] seedOf(KeyPair pair) {
        return ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A perfectly good key pair of the wrong algorithm. EC rather than RSA because it is quick. */
    private static KeyPair generateEc() {
        try {
            return KeyPairGenerator.getInstance("EC").generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sign(KeyPair pair, byte[] message) {
        try {
            Signature signature = Signature.getInstance(Ed25519.ALGORITHM);
            signature.initSign(pair.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
