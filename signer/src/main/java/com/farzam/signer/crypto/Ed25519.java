package com.farzam.signer.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Ed25519 verification, against raw 32-byte public keys.
 *
 * <p>The JDK has had Ed25519 since 15 and it needs no third-party provider, which matters on a
 * service that already carries one crypto library: the fewer implementations of signature
 * verification in this process, the fewer of them can be wrong.
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
 * last byte carrying the sign of x — that is what {@link #publicKeyFrom} unpacks. The alternative is
 * to hand-build an X.509 {@code SubjectPublicKeyInfo} by gluing a fixed twelve-byte DER prefix in
 * front, which is shorter, entirely standard, and reads like a magic number six months later.
 */
public final class Ed25519 {

    private static final String ALGORITHM = "Ed25519";

    /** A raw Ed25519 public key: one compressed curve point. */
    private static final int PUBLIC_KEY_BYTES = 32;

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
     * Checks a signature.
     *
     * <p>Returns false rather than throwing for every way a signature can fail to verify, including
     * a malformed one. The caller's response is identical in all of them — refuse to sign — and a
     * verifier whose two outcomes are "false" and "an exception the caller must remember to catch"
     * is a verifier that will eventually be used without the catch.
     *
     * @param key the approver's public key, from this service's own trusted list
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
