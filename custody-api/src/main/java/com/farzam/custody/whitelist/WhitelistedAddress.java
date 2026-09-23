package com.farzam.custody.whitelist;

import com.farzam.custody.chain.EthereumAddress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * One destination a client has declared it is willing to withdraw to.
 *
 * <p>This is the control that makes a compromised API client survivable. An attacker who can call
 * the withdrawal endpoint can still only send funds somewhere the client already listed, and listing
 * a new address is the slow step — in production it would need its own approval, a delay, or both.
 * Without it, "can call the API" and "can take the money" are the same thing.
 *
 * <p>The address is stored lower-cased by {@link EthereumAddress#normalise}, so that the whitelist
 * check is a string equality and cannot be walked past by sending the EIP-55 checksummed spelling of
 * a listed address.
 *
 * <p>{@code final}, like {@link com.farzam.custody.ledger.Account} and for the same reason: the
 * constructor validates, and a subclassable class with a throwing constructor can be turned into a
 * half-built instance.
 */
@Entity
@Table(name = "whitelisted_addresses")
@IdClass(WhitelistedAddressId.class)
public final class WhitelistedAddress {

    /**
     * A composite primary key, {@code (client_id, address)}, so the same address may be listed by
     * two clients and listed twice by neither.
     */
    @Id
    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Id
    @Column(nullable = false)
    private String address;

    /** JPA requires a no-arg constructor; it is not part of the API. */
    WhitelistedAddress() {}

    public WhitelistedAddress(UUID clientId, String address) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.address = EthereumAddress.normalise(address);
    }

    public UUID getClientId() {
        return clientId;
    }

    public String getAddress() {
        return address;
    }
}
