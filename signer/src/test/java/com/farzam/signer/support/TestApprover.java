package com.farzam.signer.support;

import com.farzam.crypto.Ed25519;
import com.farzam.events.ApprovalStatement;
import com.farzam.events.WithdrawalApproved;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

/**
 * An approver with a real Ed25519 key pair, for tests that need approvals the signer will accept.
 *
 * <p>Generated per run rather than fixed, which is the only way to test this at all: a key pair
 * committed to the repository would be sixty-four hex characters of high entropy in a file called
 * something like {@code TestApprover}, and the secret scanner would be right to stop the build. It
 * also means no test can accidentally depend on a key another test set up.
 *
 * <p>The packing and the verification both come from {@link Ed25519} in {@code common}, which is the
 * same code the signer runs in production. A test double with its own copy of the key encoding could
 * agree with itself and disagree with the service, and the tests would pass while proving nothing.
 */
public final class TestApprover {

    private final UUID id = UUID.randomUUID();
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
     * @return the approver id, as it appears on an approval and in the signer's trusted list
     */
    public UUID id() {
        return id;
    }

    /**
     * @return the public key in the form the signer's configuration takes
     */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(Ed25519.rawPublicKey(keyPair.getPublic()));
    }

    /**
     * Signs a statement honestly.
     *
     * @param statement what is being approved
     * @return the approval to put on the event
     */
    public WithdrawalApproved.Approval approve(ApprovalStatement statement) {
        return new WithdrawalApproved.Approval(id, publicKeyBase64(), sign(statement.canonicalBytes()));
    }

    /**
     * An approval whose signature is simply wrong.
     *
     * @param statement what it claims to approve
     * @return an approval carrying 64 bytes that are not a signature over anything
     */
    public WithdrawalApproved.Approval garbledApproval(ApprovalStatement statement) {
        byte[] signature = Base64.getDecoder().decode(sign(statement.canonicalBytes()));
        // Flip one bit. Ed25519 has no partial credit: this now verifies against nothing.
        signature[0] ^= 0x01;
        return new WithdrawalApproved.Approval(id, publicKeyBase64(), Base64.getEncoder().encodeToString(signature));
    }

    private String sign(byte[] message) {
        try {
            Signature signer = Signature.getInstance(Ed25519.ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(message);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("could not sign with a key this class just generated", impossible);
        }
    }

    /**
     * A convenience for the common case of approving a whole withdrawal.
     *
     * @param withdrawalId which withdrawal
     * @param destination where it goes
     * @param amountWei how much
     * @return the approval
     */
    public WithdrawalApproved.Approval approve(UUID withdrawalId, String destination, BigInteger amountWei) {
        return approve(new ApprovalStatement(withdrawalId, destination, amountWei));
    }
}
