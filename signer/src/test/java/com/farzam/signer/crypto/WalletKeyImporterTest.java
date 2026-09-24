package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farzam.signer.support.AbstractSignerTest;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * The startup import, and the four ways a deployment can get it wrong.
 *
 * <p>Each of these is an operator mistake rather than an attack, and each one matters because the
 * alternative to failing at startup is a signer that looks healthy and is wrong. The importer is
 * constructed directly rather than exercised through the context, because the interesting cases are
 * all configurations the context could not start with.
 */
@SpringBootTest
class WalletKeyImporterTest extends AbstractSignerTest {

    @Autowired
    private WalletKeys walletKeys;

    /**
     * The one that would otherwise be silent, and the reason the configured address is the
     * authority. An operator who pastes a key from another environment gets a signer that starts
     * cleanly, signs correctly, and moves funds out of a wallet nobody is reconciling.
     */
    @Test
    void aKeyThatBelongsToAnotherWalletStopsTheService() {
        var importer = new WalletKeyImporter(new HotWallet(HOT_WALLET_ADDRESS, anotherPrivateKey()), walletKeys);

        assertThatThrownBy(() -> importer.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the configured hot wallet");
    }

    /**
     * web3j's hex decoder returns nonsense rather than throwing on bad input, so the shape is
     * checked first — otherwise the operator would be told their key belongs to some other wallet
     * rather than that they had pasted something which is not a key at all.
     */
    @Test
    void somethingThatIsNotHexIsRejectedForBeingNotHex() {
        var importer = new WalletKeyImporter(new HotWallet(HOT_WALLET_ADDRESS, "nonsense"), walletKeys);

        assertThatThrownBy(() -> importer.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be 64 hex characters");
    }

    /** And the message never quotes what was typed. */
    @Test
    void theRejectionNeverEchoesTheValue() {
        String wrongLength = "ab".repeat(20);
        var importer = new WalletKeyImporter(new HotWallet(HOT_WALLET_ADDRESS, wrongLength), walletKeys);

        assertThatThrownBy(() -> importer.run(null)).hasMessageNotContaining(wrongLength);
    }

    @Test
    void aWalletWithNoStoredKeyAndNoneSuppliedCannotSignAnything() {
        var importer = new WalletKeyImporter(new HotWallet(freshAddress(), ""), walletKeys);

        assertThatThrownBy(() -> importer.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot sign anything");
    }

    /**
     * Idempotent, because it runs on every boot. A rolling restart of three instances should produce
     * one row and three log lines, not two failures and a success.
     */
    @Test
    void importingTheSameKeyAgainIsANoOp() {
        var importer = new WalletKeyImporter(new HotWallet(HOT_WALLET_ADDRESS, HOT_WALLET_PRIVATE_KEY), walletKeys);

        assertThatCode(() -> importer.run(null)).doesNotThrowAnyException();
        assertThatCode(() -> importer.run(null)).doesNotThrowAnyException();
    }

    /** A key with no matching configured address is equally fine to import against its own. */
    @Test
    void aWalletWithAStoredKeyAndNothingToImportIsAccepted() {
        var importer = new WalletKeyImporter(new HotWallet(HOT_WALLET_ADDRESS, ""), walletKeys);

        assertThatCode(() -> importer.run(null)).doesNotThrowAnyException();
    }

    private static String anotherPrivateKey() {
        try {
            ECKeyPair other = Keys.createEcKeyPair();
            return Numeric.toHexStringNoPrefixZeroPadded(other.getPrivateKey(), 64);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
