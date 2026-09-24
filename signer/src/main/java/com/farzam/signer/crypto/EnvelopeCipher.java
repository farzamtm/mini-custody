package com.farzam.signer.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption: a fresh key per wallet, wrapped by the master key.
 *
 * <pre>
 *   master key  --wraps-->  data key (one per wallet)  --encrypts-->  private key
 * </pre>
 *
 * <p><b>Why not encrypt the private key with the master key directly.</b> Two reasons, and the
 * second is the one that matters operationally. Rotating a master key that encrypts everything means
 * decrypting and re-encrypting everything, with every secret in the system in plaintext in one
 * process for the duration; rotating this one means unwrapping and re-wrapping a handful of 32-byte
 * data keys, and no private key is ever touched. And the master key is used for one small operation
 * per wallet rather than for every byte of ciphertext, which is what makes it possible to move it
 * into a KMS later without moving the data path with it.
 *
 * <p><b>AES-256-GCM, so decryption fails rather than returning rubbish.</b> GCM is authenticated: a
 * single flipped bit in the ciphertext, the IV or the additional data makes {@code doFinal} throw
 * instead of producing a plausible-looking wrong key. A wrong key here would not be caught anywhere
 * downstream — it would sign a valid transaction from an address nobody controls, and the funds
 * would be gone with no error anywhere in the logs. Authentication is what turns that into a loud
 * failure before anything is signed.
 *
 * <p><b>The wallet address is the additional authenticated data.</b> AAD is covered by the
 * authentication tag but not stored in the ciphertext, which makes it a binding: this ciphertext
 * decrypts only in the context of this address. Copy an {@code encrypted_private_key} onto another
 * row — the obvious move for anyone with write access to the signer's database and an address they
 * control — and the tag check fails. Without AAD that attack is a one-line {@code UPDATE}.
 *
 * <p><b>Every IV is random and used once.</b> Reusing an IV with the same GCM key is not a
 * theoretical weakness: two ciphertexts under one (key, IV) pair leak the XOR of their plaintexts
 * and, worse, the authentication subkey, at which point an attacker can forge tags at will. Each
 * data key here encrypts exactly one private key once, and each wrap gets its own IV, so the
 * question does not arise — but the IV is generated from {@link SecureRandom} rather than a counter
 * so that it stays true if that ever changes.
 */
@Component
public class EnvelopeCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 96 bits, the size GCM is specified for; any other length goes through a slower derivation. */
    private static final int IV_BYTES = 12;

    /** The full 128-bit tag. Truncating it weakens forgery resistance for no useful saving. */
    private static final int TAG_BITS = 128;

    private static final int DATA_KEY_BYTES = 32;

    private final MasterKey masterKey;
    private final SecureRandom random = new SecureRandom();

    EnvelopeCipher(MasterKey masterKey) {
        this.masterKey = masterKey;
    }

    /**
     * What ends up in a {@code wallet_keys} row.
     *
     * <p>Only ciphertext and public parameters — nothing here is secret on its own, which is what
     * makes it safe to pass around and store. The IV in particular is not a secret and never has
     * been; it only has to be unique.
     *
     * @param ciphertext the private key under the data key, with its GCM tag
     * @param iv the 12 bytes that ciphertext was encrypted with
     * @param wrappedDataKey the data key under the master key, its own IV prepended
     */
    public record Sealed(byte[] ciphertext, byte[] iv, byte[] wrappedDataKey) {}

    /**
     * Encrypts a private key under a data key that has never existed before.
     *
     * @param plaintext the private key; the caller still owns it and should wipe it
     * @param address the wallet this key belongs to, bound in as AAD
     * @return the three byte strings to store
     * @throws IllegalStateException if the platform's AES-GCM refuses, which it will not
     */
    public Sealed seal(byte[] plaintext, String address) {
        byte[] dataKey = new byte[DATA_KEY_BYTES];
        random.nextBytes(dataKey);
        try {
            byte[] iv = randomIv();
            byte[] ciphertext = encrypt(new SecretKeySpec(dataKey, "AES"), iv, plaintext, address);
            return new Sealed(ciphertext, iv, wrap(dataKey, address));
        } finally {
            // The data key is the only thing standing between the stored ciphertext and the private
            // key, and unlike the master key it is short-lived enough that wiping it is worth doing.
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * Recovers a private key.
     *
     * <p>The returned array is the caller's to wipe — {@link WalletKeys#withPrivateKey} is the only
     * caller precisely so that the wiping happens in one place with a {@code finally} on it.
     *
     * @param sealed what was stored
     * @param address the wallet the row is under; must be the address it was sealed with
     * @return the private key bytes
     * @throws KeyUnsealingException if anything has been altered, or the master key is the wrong one
     */
    public byte[] open(Sealed sealed, String address) {
        byte[] dataKey = unwrap(sealed.wrappedDataKey(), address);
        try {
            return decrypt(new SecretKeySpec(dataKey, "AES"), sealed.iv(), sealed.ciphertext(), address);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * The data key under the master key, with its IV prepended rather than stored beside it.
     *
     * <p>One column instead of two, and — more to the point — one thing that cannot be half-migrated
     * or half-copied. An IV in its own column is an invitation to pair it with the wrong ciphertext;
     * concatenated, the two travel together or not at all.
     */
    private byte[] wrap(byte[] dataKey, String address) {
        byte[] iv = randomIv();
        byte[] ciphertext = encrypt(masterKey.key(), iv, dataKey, address);
        byte[] wrapped = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, wrapped, 0, iv.length);
        System.arraycopy(ciphertext, 0, wrapped, iv.length, ciphertext.length);
        return wrapped;
    }

    private byte[] unwrap(byte[] wrapped, String address) {
        if (wrapped.length <= IV_BYTES) {
            throw new KeyUnsealingException("the wrapped data key is too short to contain an IV");
        }
        byte[] iv = Arrays.copyOfRange(wrapped, 0, IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(wrapped, IV_BYTES, wrapped.length);
        return decrypt(masterKey.key(), iv, ciphertext, address);
    }

    private byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    private byte[] encrypt(SecretKey key, byte[] iv, byte[] plaintext, String aad) {
        try {
            return cipher(Cipher.ENCRYPT_MODE, key, iv, aad).doFinal(plaintext);
        } catch (GeneralSecurityException impossible) {
            // A correctly-sized AES key, a 12-byte IV and no padding. If this throws, the JVM's
            // crypto providers are broken, and the only safe response is to sign nothing.
            throw new IllegalStateException("AES-GCM encryption failed", impossible);
        }
    }

    private byte[] decrypt(SecretKey key, byte[] iv, byte[] ciphertext, String aad) {
        try {
            return cipher(Cipher.DECRYPT_MODE, key, iv, aad).doFinal(ciphertext);
        } catch (GeneralSecurityException failure) {
            // The expected failure, and a security event rather than a bug: a tag mismatch means the
            // stored bytes are not what this master key and this address sealed. The message says
            // nothing about which part failed, because "wrong address" and "wrong master key" are
            // useful distinctions to an attacker and not to anybody else. The cause carries the
            // provider's own words for whoever reads the stack trace.
            throw new KeyUnsealingException("the stored key did not decrypt and authenticate", failure);
        }
    }

    private Cipher cipher(int mode, SecretKey key, byte[] iv, String aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
