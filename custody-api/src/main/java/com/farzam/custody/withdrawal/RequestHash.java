package com.farzam.custody.withdrawal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The fingerprint that tells "the client retried" apart from "the client reused the key".
 *
 * <p>An idempotency key on its own only answers "have I seen this key". It cannot answer the
 * question that matters, which is whether the request behind it is the same request. Without the
 * hash, a client that recycled a key would silently get back somebody else's withdrawal — including
 * its destination — and believe it had sent funds where it asked.
 *
 * <p><b>Why not hash the raw request body.</b> The obvious reading of "SHA-256 of the request body"
 * is the bytes on the wire, and that is wrong here: reordered JSON keys, a changed indentation, an
 * added optional field the server ignores, all produce a different hash for a request that means
 * exactly the same thing. A client retrying through a library that re-serialises its payload would
 * get a {@code 409} for doing nothing wrong. So the hash is taken over the fields that decide what
 * the withdrawal <em>does</em>, in a fixed order, after normalisation.
 *
 * <p>The {@code mini-custody:withdrawal:v1} prefix is a domain separator. It costs nothing and it
 * means a digest produced here can never be mistaken for one produced by the approval signing in M3,
 * which uses the same construction with its own prefix. The {@code v1} leaves room to change the
 * canonical form later without old and new hashes colliding.
 */
public final class RequestHash {

    private static final String DOMAIN = "mini-custody:withdrawal:v1";

    private RequestHash() {}

    /**
     * Hashes the fields that define a withdrawal request.
     *
     * @param accountId the account being debited
     * @param destination the destination, already lower-cased by
     *     {@link com.farzam.custody.chain.EthereumAddress#normalise}
     * @param amountWei the amount, in wei
     * @return the digest as lower-case hex
     */
    public static String of(UUID accountId, String destination, BigInteger amountWei) {
        // A pipe cannot appear in a UUID, in an 0x-prefixed hex address or in a decimal integer, so
        // the join is unambiguous: no combination of field values can produce the same string as a
        // different combination. That property is the whole job of a canonical form.
        String canonical = String.join("|", DOMAIN, accountId.toString(), destination, amountWei.toString());
        return HexFormat.of().formatHex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Whether two request hashes are the same, compared in constant time.
     *
     * <p>{@code String.equals} returns as soon as two characters differ, so how long it took leaks
     * how many leading characters matched. Repeated against a chosen input that is the classic way to
     * recover a digest a byte at a time.
     *
     * <p>The leak is thin here — an attacker comparing against a hash of their own request already
     * knows both sides, and recovering the stored hash would still leave SHA-256 to invert before it
     * said anything about somebody else's withdrawal. It is fixed anyway because
     * {@link MessageDigest#isEqual} costs nothing, because "this particular timing leak is probably
     * harmless" is an argument that ages badly as code moves, and because find-sec-bugs is right that
     * a money path should not have to make it.
     *
     * @param expected the hash stored with the original withdrawal
     * @param actual the hash of the request that just arrived
     * @return true if they are identical
     */
    public static boolean matches(String expected, String actual) {
        return MessageDigest
                .isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to ship SHA-256. If this one does not, nothing else here is
            // going to work either.
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }
}
