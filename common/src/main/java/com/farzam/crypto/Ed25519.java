package com.farzam.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Ed25519 signing and verification, against raw 32-byte keys.
 *
 * <p>The JDK has had Ed25519 since 15 and it needs no third-party provider, which matters on a
 * service that already carries one crypto library: the fewer implementations of signature
 * verification in this process, the fewer of them can be wrong.
 *
 * <p><b>Why this lives in {@code common} rather than in either service.</b> Both of them verify the
 * same signatures over the same {@link com.farzam.events.ApprovalStatement}: custody-api before it
 * will record an approval, the signer again before it will touch a private key. Two copies of the
 * unpacking below would be two chances to get the byte order wrong, and the failure mode of a
 * disagreement is not a crash — it is custody-api accepting an approval that the signer then
 * refuses, leaving the withdrawal stuck with both services convinced they are right. The duplication
 * that {@code docs/adr/0009} accepts for the outbox is Spring infrastructure, which cannot come here
 * at all; this is fifty lines of pure JDK with no such obstacle.
 *
 * <p><b>Why there is a {@link #sign} here and not only a {@link #verify}.</b> Until M7 nothing in
 * either service held an Ed25519 private key — approvers signed elsewhere and both services only
 * ever checked. M7 gives the signer a key of its own so it can authenticate the results it reports
 * back, and custody-api the matching public key so it can refuse a result the signer did not send.
 * See {@link com.farzam.events.EventSignature} and ADR 0012. Putting the signing half next to the
 * verifying half is the same argument as above: one implementation of the packing, one of the
 * algorithm name, and a round trip that is a testable property rather than two hopeful assertions.
 *
 * <p><b>Why Ed25519 for approvals and secp256k1 for transactions.</b> They are answering different
 * questions. The transaction signature has to be one Ethereum will accept, so the curve is not a
 * choice. The approval signature is internal, so it can be the better algorithm: Ed25519 derives its
 * per-signature nonce deterministically from the key and the message as part of the specification,
 * rather than as the RFC 6979 bolt-on ECDSA needs. A reused or predictable nonce leaks the private
 * key outright — it is how the PS3's signing key was recovered — and a scheme where that cannot be
 * got wrong is worth choosing when nothing forces the other one.
 *
 * <p><b>The 32 bytes are not an encoding anybody's {@code KeyFactory} takes directly.</b> An Ed25519
 * public key on the wire is the curve point's y coordinate, little-endian, with the top bit of the
 * last byte carrying the sign of x — that is what {@link #publicKeyFrom} unpacks and
 * {@link #rawPublicKey} packs. The alternative is to hand-build an X.509 {@code SubjectPublicKeyInfo}
 * by gluing a fixed twelve-byte DER prefix in front, which is shorter, entirely standard, and reads
 * like a magic number six months later.
 */
public final class Ed25519 {

    /** The JCA name, for {@code KeyFactory}, {@code KeyPairGenerator} and {@code Signature} alike. */
    public static final String ALGORITHM = "Ed25519";

    /** A raw Ed25519 public key: one compressed curve point. */
    private static final int PUBLIC_KEY_BYTES = 32;

    /**
     * A raw Ed25519 private key: the seed the rest of the key is derived from.
     *
     * <p>The same width as a public key and not the same kind of thing. An Ed25519 private key is
     * this seed hashed into a scalar and a prefix, and the seed is what every wire format and every
     * key-generation tool actually hands over, so it is what {@link #privateKeyFrom} takes.
     */
    private static final int PRIVATE_KEY_BYTES = 32;

    /** (r, s), 32 bytes each. */
    private static final int SIGNATURE_BYTES = 64;

    private static final int SIGN_BIT = 0x80;

    private Ed25519() {}

    /**
     * Reads a raw public key.
     *
     * <p><b>This checks the encoding, not the mathematics.</b> The JDK's {@code KeyFactory} builds a
     * key from any y it is given and leaves "is that actually a point on the curve" to verification
     * time. So 32 bytes of nonsense produce a key object rather than an exception, and the failure
     * surfaces later as signatures from that approver never verifying — which is fail-closed, and is
     * diagnosed at the first withdrawal rather than at startup. There is a test that says so.
     *
     * @param raw the 32 bytes as they travel on the wire
     * @return a key {@link #verify} can use
     * @throws IllegalArgumentException if it is not 32 bytes, or the provider rejects it outright
     */
    public static PublicKey publicKeyFrom(byte[] raw) {
        if (raw.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "an Ed25519 public key is " + PUBLIC_KEY_BYTES + " bytes, got " + raw.length);
        }

        byte[] littleEndian = raw.clone();
        boolean xOdd = (littleEndian[PUBLIC_KEY_BYTES - 1] & SIGN_BIT) != 0;
        littleEndian[PUBLIC_KEY_BYTES - 1] &= (byte) ~SIGN_BIT;

        // BigInteger reads big-endian, the wire format is little-endian.
        byte[] bigEndian = new byte[PUBLIC_KEY_BYTES];
        for (int i = 0; i < PUBLIC_KEY_BYTES; i++) {
            bigEndian[i] = littleEndian[PUBLIC_KEY_BYTES - 1 - i];
        }

        try {
            EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, bigEndian));
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
        } catch (GeneralSecurityException rejected) {
            throw new IllegalArgumentException("not a valid Ed25519 public key", rejected);
        }
    }

    /**
     * Writes a public key back out in the form it travels in.
     *
     * <p>The exact inverse of {@link #publicKeyFrom}, and it is here for the same reason that method
     * is: a key the JDK generates is a curve point, while everything that stores or transmits one —
     * {@code approvers.public_key}, the signer's trusted list, an approval on the wire — holds the 32
     * packed bytes instead. Something has to do the packing, and keeping it next to the unpacking is
     * what makes the round trip one testable property rather than two assertions that can drift.
     *
     * @param key an Ed25519 public key
     * @return its raw 32-byte encoding: y little-endian, with the sign of x in the top bit
     */
    public static byte[] rawPublicKey(PublicKey key) {
        EdECPoint point = ((EdECPublicKey) key).getPoint();

        // toByteArray is big-endian and variably sized: it drops leading zero bytes and adds one if
        // the top bit would otherwise read as a sign. Copy from the right-hand end of both.
        byte[] bigEndian = point.getY().toByteArray();
        byte[] littleEndian = new byte[PUBLIC_KEY_BYTES];
        for (int i = 0; i < Math.min(bigEndian.length, PUBLIC_KEY_BYTES); i++) {
            littleEndian[i] = bigEndian[bigEndian.length - 1 - i];
        }

        if (point.isXOdd()) {
            littleEndian[PUBLIC_KEY_BYTES - 1] |= (byte) SIGN_BIT;
        }
        return littleEndian;
    }

    /**
     * Reads a raw private key.
     *
     * <p>Throws rather than returning an empty optional, and unlike {@link #publicKeyFrom} the
     * failure is not something to diagnose later at the first withdrawal. A service holding a
     * malformed signing key cannot sign anything at all, so the only useful moment to find out is
     * startup, and the only useful behaviour is to refuse to start.
     *
     * @param seed the 32 bytes the key is derived from
     * @return a key {@link #sign} can use
     * @throws IllegalArgumentException if it is not 32 bytes, or the provider rejects it outright
     */
    public static PrivateKey privateKeyFrom(byte[] seed) {
        if (seed.length != PRIVATE_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "an Ed25519 private key is " + PRIVATE_KEY_BYTES + " bytes, got " + seed.length);
        }

        try {
            // The spec copies the array, but it is cloned here anyway: the caller's buffer is
            // something it may well want to zero out afterwards, and a defensive copy is cheaper
            // than depending on a provider's internals staying the way they are today.
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed.clone()));
        } catch (GeneralSecurityException rejected) {
            throw new IllegalArgumentException("not a valid Ed25519 private key", rejected);
        }
    }

    /**
     * Signs a message.
     *
     * <p>Throws rather than returning a sentinel, which is the opposite of {@link #verify} and for
     * the opposite reason. A verifier has two legitimate answers and the caller does the same thing
     * for every kind of "no". A signer has one: either these are the bytes, or the key is broken and
     * nothing downstream can proceed. Returning null or an empty array would let a caller publish an
     * unsigned message and discover it at the consumer, which is exactly the failure this whole
     * mechanism exists to make impossible.
     *
     * @param key the signer's private key
     * @param message the canonical bytes to sign
     * @return the 64 signature bytes
     * @throws IllegalStateException if the key cannot sign — a misconfiguration, not a bad input
     */
    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException broken) {
            throw new IllegalStateException("could not sign with this Ed25519 key", broken);
        }
    }

    /**
     * Checks a signature.
     *
     * <p>Returns false rather than throwing for every way a signature can fail to verify, including
     * a malformed one. The caller's response is identical in all of them — refuse — and a verifier
     * whose two outcomes are "false" and "an exception the caller must remember to catch" is a
     * verifier that will eventually be used without the catch.
     *
     * @param key the approver's public key, from the verifier's own records
     * @param message the canonical bytes that should have been signed
     * @param signature the 64 signature bytes
     * @return true only if this key signed exactly these bytes
     */
    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        if (signature.length != SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException invalid) {
            return false;
        }
    }
}
