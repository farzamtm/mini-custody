package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farzam.signer.support.AbstractSignerTest;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The key store against real Postgres: storing, lending, and the two ways it says no. */
@SpringBootTest
class WalletKeysTest extends AbstractSignerTest {

    @Autowired
    private WalletKeys walletKeys;

    @Test
    void aStoredKeyIsLentBackExactlyAndThenWiped() {
        String address = freshAddress();
        byte[] privateKey = randomKeyBytes();

        assertThat(walletKeys.store(address, privateKey)).isTrue();
        assertThat(walletKeys.contains(address)).isTrue();

        // The array the lambda is handed is the one that gets zeroed, so it is compared inside and
        // its identity kept to check that afterwards.
        byte[] lent = walletKeys.withPrivateKey(address, key -> {
            assertThat(key).isEqualTo(privateKey);
            return key;
        });
        assertThat(lent).containsOnly((byte) 0);
    }

    /** The importer runs on every boot, so a second store for one address has to do nothing. */
    @Test
    void storingTheSameAddressTwiceDoesNothingTheSecondTime() {
        String address = freshAddress();

        assertThat(walletKeys.store(address, randomKeyBytes())).isTrue();
        assertThat(walletKeys.store(address, randomKeyBytes())).isFalse();
    }

    /**
     * A configuration error rather than an attack: the configured hot wallet does not match anything
     * in the store, usually because the import was skipped or pointed at another database.
     */
    @Test
    void anAddressWithNoKeyIsAnUnknownWallet() {
        String never = freshAddress();

        assertThat(walletKeys.contains(never)).isFalse();
        assertThatThrownBy(() -> walletKeys.withPrivateKey(never, key -> key))
                .isInstanceOf(UnknownWalletException.class)
                .hasMessageContaining(never);
    }

    /**
     * The lambda's exception has to come out, not be swallowed by the wiping. If a signing failure
     * were turned into a successful call returning nothing, the listener would record a signature
     * that does not exist.
     */
    @Test
    void aFailureInsideTheLambdaPropagatesAndTheKeyIsStillWiped() {
        String address = freshAddress();
        walletKeys.store(address, randomKeyBytes());

        byte[][] captured = new byte[1][];
        assertThatThrownBy(() -> walletKeys.withPrivateKey(address, key -> {
            captured[0] = key;
            throw new IllegalStateException("signing blew up");
        })).isInstanceOf(IllegalStateException.class).hasMessage("signing blew up");

        assertThat(captured[0]).containsOnly((byte) 0);
    }

    private static byte[] randomKeyBytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
