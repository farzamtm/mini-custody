package com.farzam.signer.crypto;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one wallet this service signs from.
 *
 * <p>One address, hard-configured, rather than a wallet chosen per withdrawal. A hot wallet is the
 * tier that is online and automated, so the interesting question about it is how much can leave it
 * without a human, and that question only has an answer if there is exactly one of them and its cap
 * is a constant — see {@code signer.policy.max-transaction-wei}. Cold and warm wallets, HD
 * derivation and a key per client are all real things this project does not have, and the README
 * says so rather than this pretending otherwise.
 *
 * @param address the wallet, lower-case hex. Authoritative: the imported key must derive to it, so a
 *     key pointed at the wrong deployment fails at startup rather than signing from an address
 *     nobody is reconciling.
 * @param importPrivateKey the key to import at startup, hex, from the environment. Empty in any
 *     deployment where the key is already in the database, which after the first boot is all of
 *     them.
 */
@ConfigurationProperties("signer.hot-wallet")
public record HotWallet(String address, String importPrivateKey) {

    /**
     * Normalises both, so that everything downstream can compare addresses with {@code equals}.
     */
    public HotWallet {
        address = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        importPrivateKey = importPrivateKey == null ? "" : importPrivateKey.strip();
    }

    /**
     * The address, insisting that there is one.
     *
     * @return the configured wallet address
     * @throws IllegalStateException if none is configured
     */
    public String requireAddress() {
        if (address.isBlank()) {
            throw new IllegalStateException("signer.hot-wallet.address is not set; there is nothing to sign from");
        }
        return address;
    }

    /**
     * @return whether a key was supplied to import
     */
    public boolean hasKeyToImport() {
        return !importPrivateKey.isBlank();
    }

    /**
     * Masked, because a record's generated {@code toString} would not be.
     *
     * <p>This is not hypothetical tidiness. Spring logs {@code @ConfigurationProperties} beans when
     * binding fails, actuator's {@code configprops} endpoint renders them, and every debugger and
     * exception message in the JVM reaches for {@code toString} first. The generated one would put a
     * private key into all of those, and the one place a secret must never appear is a log, because
     * logs are the part of a system that is deliberately copied somewhere else and kept.
     *
     * @return the address, and the fact that a key is or is not set
     */
    @Override
    public String toString() {
        return "HotWallet[address=" + address + ", importPrivateKey=" + (hasKeyToImport() ? "<set>" : "<unset>") + "]";
    }
}
