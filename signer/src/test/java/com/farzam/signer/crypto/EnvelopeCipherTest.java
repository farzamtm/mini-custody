package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Envelope encryption, without Spring or Postgres.
 *
 * <p>These run in microseconds and cover the properties that make the key store worth having, so
 * they are worth having separately from the integration test that exercises the same code as a side
 * effect of signing something. An integration test tells you the happy path works; it cannot tell
 * you that moving a row to another address fails, because it never moves one.
 */
class EnvelopeCipherTest {

    private static final String ADDRESS = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    private static final String ANOTHER_ADDRESS = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";

    private final EnvelopeCipher cipher = new EnvelopeCipher(new MasterKey(randomKey()));

    @Test
    void aSealedKeyComesBackExactly() {
        byte[] privateKey = randomBytes(32);

        assertThat(cipher.open(cipher.seal(privateKey, ADDRESS), ADDRESS)).isEqualTo(privateKey);
    }

    /**
     * The reason each wallet gets its own data key rather than sharing one, made visible: no two
     * seals produce the same stored bytes, so nothing about one ciphertext says anything about
     * another.
     */
    @Test
    void sealingTheSameKeyTwiceProducesDifferentCiphertext() {
        byte[] privateKey = randomBytes(32);

        EnvelopeCipher.Sealed first = cipher.seal(privateKey, ADDRESS);
        EnvelopeCipher.Sealed second = cipher.seal(privateKey, ADDRESS);

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.wrappedDataKey()).isNotEqualTo(second.wrappedDataKey());
    }

    /**
     * What the additional authenticated data buys. Copying an encrypted key onto another row is a
     * one-line UPDATE for anyone with write access to the signer's database, and it is how you would
     * make this service sign from an address you control. Binding the address into the tag makes the
     * copy useless.
     */
    @Test
    void aKeyCopiedOntoAnotherAddressDoesNotDecrypt() {
        EnvelopeCipher.Sealed sealed = cipher.seal(randomBytes(32), ADDRESS);

        assertThatThrownBy(() -> cipher.open(sealed, ANOTHER_ADDRESS)).isInstanceOf(KeyUnsealingException.class);
    }

    /**
     * GCM is authenticated, which is the difference between a loud failure and a signature from a
     * key nobody holds. A wrong key recovered silently would produce a perfectly valid transaction
     * from an address nobody is reconciling.
     */
    @Test
    void aSingleAlteredByteAnywhereIsRejected() {
        EnvelopeCipher.Sealed sealed = cipher.seal(randomBytes(32), ADDRESS);

        assertThatThrownBy(
                () -> cipher.open(
                        new EnvelopeCipher.Sealed(
                                flipFirstBit(sealed.ciphertext()),
                                sealed.iv(),
                                sealed.wrappedDataKey()),
                        ADDRESS))
                .isInstanceOf(KeyUnsealingException.class);
        assertThatThrownBy(
                () -> cipher.open(
                        new EnvelopeCipher.Sealed(
                                sealed.ciphertext(),
                                flipFirstBit(sealed.iv()),
                                sealed.wrappedDataKey()),
                        ADDRESS))
                .isInstanceOf(KeyUnsealingException.class);
        assertThatThrownBy(
                () -> cipher.open(
                        new EnvelopeCipher.Sealed(
                                sealed.ciphertext(),
                                sealed.iv(),
                                flipFirstBit(sealed.wrappedDataKey())),
                        ADDRESS))
                .isInstanceOf(KeyUnsealingException.class);
    }

    @Test
    void aWrappedDataKeyTooShortToHoldAnIvIsRejectedRatherThanIndexedInto() {
        EnvelopeCipher.Sealed sealed = cipher.seal(randomBytes(32), ADDRESS);

        assertThatThrownBy(
                () -> cipher.open(new EnvelopeCipher.Sealed(sealed.ciphertext(), sealed.iv(), new byte[4]), ADDRESS))
                .isInstanceOf(KeyUnsealingException.class)
                .hasMessageContaining("too short");
    }

    /** A signer deployed with the wrong master key must not be able to read the store at all. */
    @Test
    void anotherMasterKeyCannotOpenIt() {
        EnvelopeCipher.Sealed sealed = cipher.seal(randomBytes(32), ADDRESS);
        EnvelopeCipher other = new EnvelopeCipher(new MasterKey(randomKey()));

        assertThatThrownBy(() -> other.open(sealed, ADDRESS)).isInstanceOf(KeyUnsealingException.class);
    }

    private static byte[] flipFirstBit(byte[] original) {
        byte[] altered = original.clone();
        altered[0] ^= 0x01;
        return altered;
    }

    private static String randomKey() {
        return Base64.getEncoder().encodeToString(randomBytes(32));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
