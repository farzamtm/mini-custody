package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.farzam.events.ApprovalStatement;
import com.farzam.signer.support.TestApprover;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Raw-key unpacking and verification, against keys the JDK generated. */
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

        PublicKey unpacked = Ed25519.publicKeyFrom(TestApprover.rawPublicKey(pair.getPublic()));

        assertThat(unpacked).isEqualTo(pair.getPublic());
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
     * nonsense public key in {@code signer.policy.trusted-approvers} passes the startup parse, and
     * what happens instead is that every signature attributed to that approver fails to verify. The
     * signer refuses the withdrawal. Fail-closed, but diagnosed at the first withdrawal rather than
     * at boot.
     */
    @Test
    void bytesThatAreNotACurvePointAreAcceptedAsAKeyAndThenVerifyNothing() {
        byte[] notAPoint = new byte[32];
        Arrays.fill(notAPoint, (byte) 0xFF);

        PublicKey accepted = Ed25519.publicKeyFrom(notAPoint);

        assertThat(Ed25519.verify(accepted, MESSAGE, new byte[64])).isFalse();
    }

    /**
     * Ties the test double to the production verifier. {@link TestApprover} is what makes the signer
     * say yes in every other test in this module, so if its key packing or its signing disagreed
     * with this class, those tests would be proving something about a private arrangement between
     * two test helpers.
     */
    @Test
    void theTestApproverProducesKeysAndSignaturesThisVerifierAccepts() {
        TestApprover approver = TestApprover.generate();
        var statement = new ApprovalStatement(UUID.randomUUID(), "0x" + "c".repeat(40), BigInteger.TEN);

        PublicKey key = Ed25519.publicKeyFrom(Base64.getDecoder().decode(approver.publicKeyBase64()));
        byte[] signature = Base64.getDecoder().decode(approver.approve(statement).signature());

        assertThat(Ed25519.verify(key, statement.canonicalBytes(), signature)).isTrue();
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sign(KeyPair pair, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(pair.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
