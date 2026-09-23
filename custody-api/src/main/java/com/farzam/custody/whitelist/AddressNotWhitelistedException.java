package com.farzam.custody.whitelist;

import java.io.Serial;
import java.util.UUID;

/**
 * A withdrawal named a destination the client has not declared.
 *
 * <p>Maps to {@code 422} with code {@code ADDRESS_NOT_WHITELISTED}. The request is perfectly
 * well-formed; it is simply not allowed, and no amount of retrying will change that until the
 * address is added.
 *
 * <p>Checked before the funds are held, so a rejected withdrawal leaves the balance untouched.
 */
public class AddressNotWhitelistedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AddressNotWhitelistedException(UUID clientId, String address) {
        super("client %s has not whitelisted %s".formatted(clientId, address));
    }
}
