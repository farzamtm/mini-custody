package com.farzam.signer.crypto;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * Puts the hot wallet's private key into the key store, once, at startup.
 *
 * <p>This is the closest thing this project has to a key ceremony, and the gap between the two is
 * most of the difference between a demo and a custody system. A real one generates the key inside an
 * HSM, in a room, witnessed, with the backup shares split and carried to separate safes, and the
 * private key never exists outside the module at any point. This reads it from an environment
 * variable. What the two have in common is only that both happen once and both bind a key to an
 * address that is recorded elsewhere.
 *
 * <p><b>The configured address is the authority, not the key.</b> The address is derived from the
 * key and compared, and a mismatch stops the service. Without that check, an operator who pasted the
 * wrong key into the wrong environment would get a signer that starts cleanly, signs correctly, and
 * moves funds out of a wallet nobody is reconciling — a failure that shows up in the accounts weeks
 * later rather than in the logs immediately.
 *
 * <p><b>It runs on every boot and is idempotent.</b> {@code wallet_keys} is keyed by address and the
 * insert does nothing on conflict, so a rolling restart of three instances produces one row and
 * three log lines, rather than two failures and a success. The variable can then be removed from the
 * environment, which is worth doing: after the first boot it is a copy of a secret with no reader.
 */
@Component
class WalletKeyImporter implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WalletKeyImporter.class);

    /** 32 bytes. Anything else is not a secp256k1 private key, whatever it is. */
    private static final int PRIVATE_KEY_BYTES = 32;

    /** Those 32 bytes as hex, with or without the prefix. */
    private static final Pattern PRIVATE_KEY_HEX = Pattern.compile("^(0x)?[0-9a-fA-F]{64}$");

    private final HotWallet hotWallet;
    private final WalletKeys walletKeys;

    WalletKeyImporter(HotWallet hotWallet, WalletKeys walletKeys) {
        this.hotWallet = hotWallet;
        this.walletKeys = walletKeys;
    }

    /**
     * @param args ignored; the key comes from configuration, never from the command line, because a
     *     command line is visible in {@code ps} to every other process on the machine
     * @throws IllegalStateException if the key does not belong to the configured wallet, or if
     *     neither a key nor a stored one is available
     */
    @Override
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "The only value logged is an Ethereum address that has just been "
                    + "checked against ^0x[0-9a-f]{40}$ by being derived from key bytes.")
    public void run(ApplicationArguments args) {
        String address = hotWallet.requireAddress();

        if (!hotWallet.hasKeyToImport()) {
            if (!walletKeys.contains(address)) {
                throw new IllegalStateException(
                        "no key is stored for the hot wallet " + address
                                + " and none was supplied to import; this signer cannot sign anything");
            }
            LOG.info("hot wallet {} is already in the key store", address);
            return;
        }

        byte[] privateKey = decode(hotWallet.importPrivateKey());
        try {
            String derived = "0x" + Keys.getAddress(ECKeyPair.create(privateKey));
            // Compared as the twenty bytes they stand for, not as text. Two spellings of one address
            // differ only in case, and case-folding a string before making a security decision about
            // it is locale-dependent and, in some scripts, not reversible — find-sec-bugs flags it,
            // and an address comparison has no need to go anywhere near the question.
            if (!Arrays.equals(Numeric.hexStringToByteArray(derived), Numeric.hexStringToByteArray(address))) {
                // Both addresses are public information, and naming them is the whole value of the
                // message: "the key you gave me belongs to this other wallet" is diagnosable in
                // seconds, "key mismatch" is not.
                throw new IllegalStateException(
                        "the supplied private key belongs to " + derived + ", not the configured hot wallet "
                                + address);
            }

            if (walletKeys.store(address, privateKey)) {
                LOG.info("imported and sealed the hot wallet key for {}", address);
            } else {
                LOG.info("hot wallet {} is already in the key store; the supplied key was not needed", address);
            }
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
    }

    /**
     * Checks the shape before decoding, rather than catching something afterwards.
     *
     * <p>Not a style preference: {@code Numeric.hexStringToByteArray} does not throw on invalid
     * input. It builds each byte with {@link Character#digit}, which returns -1 for a character that
     * is not a hex digit, so nonsense decodes quietly into different nonsense. The mismatch would
     * still be caught — the derived address would not match the configured one — but the operator
     * would be told their key belongs to some other wallet rather than that they had pasted
     * something that is not a key.
     *
     * <p>The message never quotes the value.
     */
    private static byte[] decode(String hex) {
        if (!PRIVATE_KEY_HEX.matcher(hex).matches()) {
            throw new IllegalStateException(
                    "signer.hot-wallet.import-private-key must be " + PRIVATE_KEY_BYTES * 2
                            + " hex characters, optionally 0x-prefixed");
        }
        return Numeric.hexStringToByteArray(hex);
    }
}
