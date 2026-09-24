package com.farzam.custody.support;

import com.farzam.crypto.Ed25519;
import com.farzam.events.ApprovalStatement;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

/**
 * An approver with a real Ed25519 key pair, for tests that need approvals this service will accept.
 *
 * <p>Generated per run rather than fixed, which is the only way to test this at all: a key pair
 * committed to the repository would be high-entropy bytes in a file called something like
 * {@code TestApprover}, and the secret scanner would be right to stop the build. It also means no
 * test can accidentally depend on a key another test set up.
 *
 * <p>The packing comes from {@link Ed25519} in {@code common}, the same code the service verifies
 * with. A test double with its own copy of the key encoding could agree with itself and disagree
 * with production, and the tests would pass while proving nothing.
 *
 * <p>The signer has a class of the same name and the same shape. They are not shared, and the
 * duplication is about twenty lines: this one produces base64 strings for an HTTP body, that one
 * produces {@code WithdrawalApproved.Approval} records for an event, and the part they have in
 * common is already in {@code common}. What is worth watching is not the duplication but the
 * agreement — and that is not a matter of trust, because both sign the canonical bytes of an
 * {@link ApprovalStatement} and there is one implementation of those.
 */
public final class TestApprover {

    private final KeyPair keyPair;

    private TestApprover(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /**
     * @return a new approver with a fresh key pair
     */
    public static TestApprover generate() {
        try {
            return new TestApprover(KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("this JDK has no Ed25519", impossible);
        }
    }

    /**
     * @return the public key in the form {@code POST /dev/approvers} takes
     */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(Ed25519.rawPublicKey(keyPair.getPublic()));
    }

    /**
     * Signs what an approver is actually agreeing to.
     *
     * @param withdrawalId which withdrawal
     * @param destination where the funds go
     * @param amountWei how much
     * @return the signature, base64, as the approval endpoint takes it
     */
    public String sign(UUID withdrawalId, String destination, BigInteger amountWei) {
        return sign(new ApprovalStatement(withdrawalId, destination, amountWei));
    }

    /**
     * @param statement what is being approved
     * @return the signature over its canonical bytes, base64
     */
    public String sign(ApprovalStatement statement) {
        return signBytes(statement.canonicalBytes());
    }

    /**
     * A signature that is the right shape and verifies against nothing.
     *
     * @param statement what it claims to approve
     * @return 64 bytes that are a signature over something else entirely
     */
    public String garbledSignature(ApprovalStatement statement) {
        byte[] signature = Base64.getDecoder().decode(sign(statement));
        // Flip one bit. Ed25519 has no partial credit: this now verifies against nothing.
        signature[0] ^= 0x01;
        return Base64.getEncoder().encodeToString(signature);
    }

    private String signBytes(byte[] message) {
        try {
            Signature signer = Signature.getInstance(Ed25519.ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(message);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("could not sign with a key this class just generated", impossible);
        }
    }
}
