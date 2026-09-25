package com.farzam.signer.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.farzam.crypto.Ed25519;
import com.farzam.events.EventSignature;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The startup contract for the key the signer authenticates its results with.
 *
 * <p>No Spring context: the whole subject is what the constructor does with a configured string, and
 * a test that needed a broker and a chain to find out would be slower and prove less.
 *
 * <p>Every case here is a deployment mistake, and every one of them has to stop the service. The
 * reasoning is in {@link ResultSigningKey} and ADR 0012, and it is the opposite of the fail-closed
 * default the policy list uses: a signer that cannot sign is safe, while one that signs transactions
 * and cannot authenticate its own reports broadcasts on chain and then has every report rejected,
 * stranding the withdrawal with the client's funds held.
 */
class ResultSigningKeyTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void anAbsentKeyStopsTheServiceStarting(String configured) {
        assertThatIllegalStateException().isThrownBy(() -> new ResultSigningKey(configured))
                .withMessageContaining("signer.results.signing-key is not set");
    }

    @Test
    void aKeyThatIsNotBase64StopsTheServiceStarting() {
        assertThatIllegalStateException().isThrownBy(() -> new ResultSigningKey("not base64 !!!"))
                .withMessageContaining("not valid base64");
    }

    /**
     * Base64 that decodes cleanly and is the wrong number of bytes.
     *
     * <p>Worth its own case because it is the plausible mistake: pasting the DER private key, or the
     * public key, or the master key, instead of the raw 32-byte seed. All three decode.
     */
    @Test
    void aKeyOfTheWrongLengthStopsTheServiceStarting() {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatIllegalStateException().isThrownBy(() -> new ResultSigningKey(sixteenBytes))
                .withMessageContaining("not an Ed25519 private key");
    }

    /**
     * The good case, asserted through a verification rather than a "no exception was thrown".
     *
     * <p>A constructor that accepted the seed and then signed with something else would pass any
     * weaker assertion, and would fail only at custody-api — which is a long way from here.
     */
    @Test
    void aValidSeedProducesSignaturesTheMatchingPublicKeyVerifies() {
        KeyPair pair = generate();
        byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();

        var key = new ResultSigningKey(Base64.getEncoder().encodeToString(seed));
        String message = "{\"eventType\":\"withdrawal.broadcast.v1\"}";

        assertThat(EventSignature.verify(pair.getPublic(), message, key.sign(message))).isTrue();
    }

    /** Surrounding whitespace is tolerated, because an environment variable often carries it. */
    @Test
    void aSeedWithWhitespaceAroundItStillWorks() {
        KeyPair pair = generate();
        byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();

        var key = new ResultSigningKey("  " + Base64.getEncoder().encodeToString(seed) + "\n");

        assertThat(EventSignature.verify(pair.getPublic(), "anything", key.sign("anything"))).isTrue();
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (Exception impossible) {
            throw new IllegalStateException("this JDK has no Ed25519", impossible);
        }
    }
}
