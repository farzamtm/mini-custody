package com.farzam.signer.crypto;

import java.io.Serial;

/**
 * The signer was asked to sign from an address it has no key for.
 *
 * <p>A configuration error rather than an attack: the hot wallet address in {@code application.yml}
 * does not match anything in {@code wallet_keys}, usually because the import step was skipped or
 * pointed at a different database. Carrying the address is safe — an Ethereum address is public by
 * definition, and it is the one piece of information that makes this diagnosable from a log line.
 */
public class UnknownWalletException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param address the wallet with no key
     */
    public UnknownWalletException(String address) {
        super("no wallet key is stored for " + address);
    }
}
