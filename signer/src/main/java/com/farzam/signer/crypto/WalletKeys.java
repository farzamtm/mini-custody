package com.farzam.signer.crypto;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The key store: the only code in the system that can turn a row into a usable private key.
 *
 * <p>The interesting part of the interface is what it does not have. There is no {@code
 * loadPrivateKey(address)}, because a method that hands out key bytes makes every caller responsible
 * for wiping them and guarantees that one of them eventually will not. {@link #withPrivateKey}
 * lends the bytes for the length of a lambda and zeroes them in a {@code finally}, so the window in
 * which a private key exists in this process is one stack frame long and cannot be extended by
 * forgetting something.
 *
 * <p><b>What that still does not achieve, stated plainly.</b> web3j's {@code ECKeyPair} holds the
 * private key as a {@link java.math.BigInteger}, which is immutable and cannot be wiped, and the
 * signing code has to construct one. So the guarantee here is narrower than it looks: the array this
 * class allocates is cleared, and a copy the crypto library made from it survives until the garbage
 * collector gets to it. Closing that gap means a signing library that works in {@code byte[]}
 * throughout, or an HSM that never returns the key at all — which is, again, the real answer.
 */
@Component
public class WalletKeys {

    /**
     * {@code on conflict do nothing}, so importing the same wallet twice is a no-op.
     *
     * <p>Not merely convenient: the importer runs on every boot, and a second row for one address is
     * not a thing this schema can express — {@code address} is the primary key. Letting the database
     * arbitrate means a rolling restart of three instances does not produce two failures and a
     * success.
     */
    private static final String INSERT = """
            insert into wallet_keys (address, encrypted_private_key, key_iv, wrapped_data_key)
            values (:address, :ciphertext, :iv, :wrappedDataKey)
            on conflict (address) do nothing
            """;

    private static final String SELECT = """
            select encrypted_private_key, key_iv, wrapped_data_key
            from wallet_keys
            where address = :address
            """;

    private static final RowMapper<EnvelopeCipher.Sealed> AS_SEALED = (rs, rowNum) -> new EnvelopeCipher.Sealed(
            rs.getBytes("encrypted_private_key"),
            rs.getBytes("key_iv"),
            rs.getBytes("wrapped_data_key"));

    private final JdbcClient jdbc;
    private final EnvelopeCipher cipher;

    WalletKeys(JdbcClient jdbc, EnvelopeCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * Seals a private key and stores it, if this address is not already known.
     *
     * @param address the wallet, lower-case hex; bound into the ciphertext as AAD
     * @param privateKey the 32 key bytes; the caller wipes them
     * @return true if a row was written, false if the address was already in the store
     */
    public boolean store(String address, byte[] privateKey) {
        EnvelopeCipher.Sealed sealed = cipher.seal(privateKey, address);
        return jdbc.sql(INSERT)
                .param("address", address)
                .param("ciphertext", sealed.ciphertext())
                .param("iv", sealed.iv())
                .param("wrappedDataKey", sealed.wrappedDataKey())
                .update() == 1;
    }

    /**
     * @param address the wallet to look for
     * @return whether the store has a key for it
     */
    public boolean contains(String address) {
        return jdbc.sql(SELECT).param("address", address).query(AS_SEALED).optional().isPresent();
    }

    /**
     * Lends a decrypted private key for the duration of one call, then wipes it.
     *
     * <p>The lambda should do the signing and return the result, and must not keep the array. There
     * is no way to enforce that in Java short of copying the bytes on the way in, which would create
     * exactly the extra copy this method exists to avoid.
     *
     * @param address the wallet to sign with
     * @param use what to do with the key
     * @param <T> whatever the caller needs back — a signature, normally
     * @return the lambda's result
     * @throws UnknownWalletException if there is no key for that address
     * @throws KeyUnsealingException if the stored bytes do not decrypt and authenticate
     */
    public <T> T withPrivateKey(String address, Function<byte[], T> use) {
        EnvelopeCipher.Sealed sealed = load(address).orElseThrow(() -> new UnknownWalletException(address));
        byte[] privateKey = cipher.open(sealed, address);
        try {
            return use.apply(privateKey);
        } finally {
            Arrays.fill(privateKey, (byte) 0);
        }
    }

    private Optional<EnvelopeCipher.Sealed> load(String address) {
        return jdbc.sql(SELECT).param("address", address).query(AS_SEALED).optional();
    }
}
