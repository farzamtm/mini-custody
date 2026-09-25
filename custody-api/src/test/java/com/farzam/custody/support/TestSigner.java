package com.farzam.custody.support;

import com.farzam.crypto.Ed25519;
import com.farzam.events.EventSignature;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Stands in for the signer service, for tests that publish results this service should accept.
 *
 * <p>Generated per run rather than fixed, for the reason {@link TestApprover} gives: a key pair in
 * the repository is high-entropy bytes in a committed file, and the secret scanner would be right to
 * stop the build.
 *
 * <p>The signing goes through {@link EventSignature}, the same code custody-api verifies with, so a
 * test cannot pass by agreeing with a second implementation that production does not use.
 */
public final class TestSigner {

    private final KeyPair keyPair;

    private TestSigner(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /**
     * @return a new signer identity with a fresh key pair
     */
    public static TestSigner generate() {
        try {
            return new TestSigner(KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair());
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("this JDK has no Ed25519", impossible);
        }
    }

    /**
     * @return the public key in the form {@code custody.signer-results.public-key} takes
     */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(Ed25519.rawPublicKey(keyPair.getPublic()));
    }

    /**
     * @param message the exact record value being published
     * @return the signature for the {@link EventSignature#HEADER} header
     */
    public String sign(String message) {
        return EventSignature.sign(keyPair.getPrivate(), message);
    }
}
