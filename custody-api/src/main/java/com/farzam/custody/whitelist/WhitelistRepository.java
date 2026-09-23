package com.farzam.custody.whitelist;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data generates both queries from the method names. */
public interface WhitelistRepository extends JpaRepository<WhitelistedAddress, WhitelistedAddressId> {

    /**
     * The whitelist check itself.
     *
     * <p>{@code exists…} rather than {@code find…}: it becomes a {@code select 1} the planner can
     * answer from the primary-key index, and there is nothing in the row worth loading.
     *
     * @param clientId whose list to search
     * @param address a lower-case address, as {@link com.farzam.custody.chain.EthereumAddress} stores them
     * @return true if this client may withdraw there
     */
    boolean existsByClientIdAndAddress(UUID clientId, String address);
}
