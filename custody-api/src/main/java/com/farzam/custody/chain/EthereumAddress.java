package com.farzam.custody.chain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one place that decides what an Ethereum address looks like and how it is stored.
 *
 * <p>An address is 20 bytes, written as {@code 0x} and 40 hex characters. The hex may be in any
 * case, and the case is not decoration: EIP-55 encodes a checksum in it, so
 * {@code 0x70997970C51812dc3A010C7d01b50e0d17dc79C8} and the all-lowercase spelling are the same
 * address, and a client that whitelists one and withdraws to the other means the same thing both
 * times.
 *
 * <p>So everything is folded to lower case before it is stored or compared. Doing that in one
 * function rather than at each call site is what keeps the whitelist check honest: a comparison
 * that normalises one side and not the other is a whitelist that can be walked straight past by
 * sending the checksummed form.
 */
public final class EthereumAddress {

    private static final Pattern FORMAT = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private EthereumAddress() {}

    /**
     * Validates an address and returns its canonical, lower-case form.
     *
     * <p>The contract already applies this pattern at the edge, so a malformed address is a 400
     * before it reaches here. This is the backstop for the paths that do not come from an HTTP
     * request — a Kafka event, a test, a future admin tool — and it is cheap.
     *
     * @param address an address in any case, with the {@code 0x} prefix
     * @return the same address in lower case
     * @throws MalformedAddressException if it is not 0x followed by 40 hex characters
     */
    public static String normalise(String address) {
        if (address == null || !FORMAT.matcher(address).matches()) {
            throw new MalformedAddressException(address);
        }
        // Locale.ROOT, not the default locale: in a Turkish locale "I".toLowerCase() is "ı", which
        // is not a hex digit. A server's locale must never change what an address means.
        return address.toLowerCase(Locale.ROOT);
    }
}
