package com.farzam.custody.whitelist;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The primary key of {@link WhitelistedAddress}.
 *
 * <p>JPA's rules for an {@code @IdClass} are strict and unusual: a public no-arg constructor, fields
 * whose names and types match the {@code @Id} fields of the entity, {@code Serializable}, and real
 * {@code equals}/{@code hashCode} because the persistence context uses it as a map key. A record
 * satisfies every one of those except the no-arg constructor, which is why this is a plain class.
 */
public class WhitelistedAddressId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID clientId;
    private String address;

    public WhitelistedAddressId() {}

    public WhitelistedAddressId(UUID clientId, String address) {
        this.clientId = clientId;
        this.address = address;
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getAddress() {
        return address;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof WhitelistedAddressId that && Objects.equals(clientId, that.clientId)
                && Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientId, address);
    }
}
