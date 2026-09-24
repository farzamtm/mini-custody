package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The master key is validated at startup or not at all.
 *
 * <p>Each of these is a deployment mistake that would otherwise surface as a failure to sign,
 * minutes or hours later, while somebody is waiting for a payment — and with a health check that had
 * been green throughout.
 */
class MasterKeyTest {

    @Test
    void aProperlySizedKeyIsAccepted() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);

        assertThat(new MasterKey(Base64.getEncoder().encodeToString(raw)).key().getAlgorithm()).isEqualTo("AES");
    }

    @Test
    void anAbsentKeyStopsTheService() {
        assertThatThrownBy(() -> new MasterKey("")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signer.master-key is not set");
    }

    /**
     * The message says the value is not base64 and stops there. This is the one string in the
     * process that must never reach a log, and a startup failure is read by whoever set it.
     */
    @Test
    void aKeyThatIsNotBase64IsRejectedWithoutQuotingIt() {
        assertThatThrownBy(() -> new MasterKey("not base64 at all !!")).isInstanceOf(IllegalStateException.class)
                .hasMessage("signer.master-key is not valid base64");
    }

    /** AES-128 where AES-256 was meant is a real and quiet downgrade. */
    @Test
    void aKeyOfTheWrongLengthIsRejected() {
        assertThatThrownBy(() -> new MasterKey(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must decode to 32 bytes, got 16");
    }
}
