package com.farzam.custody.whitelist;

import com.farzam.custody.chain.EthereumAddress;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes a client's list of permitted destinations. */
@Service
public class WhitelistService {

    private final WhitelistRepository whitelist;

    WhitelistService(WhitelistRepository whitelist) {
        this.whitelist = whitelist;
    }

    /**
     * Adds an address to a client's whitelist, or confirms it is already there.
     *
     * <p>Idempotent, and by construction rather than by checking first: the primary key is
     * {@code (client_id, address)}, so {@code save} of a row that exists is a no-op update of a row
     * with no non-key columns. Adding the same address twice therefore succeeds twice, which is what
     * a client retrying a request that already worked should see.
     *
     * @param clientId whose list to add to
     * @param address the destination, in any case
     * @return the stored entry, with the address in its canonical lower case
     * @throws com.farzam.custody.chain.MalformedAddressException if it is not an Ethereum address
     */
    @Transactional
    public WhitelistedAddress allow(UUID clientId, String address) {
        return whitelist.save(new WhitelistedAddress(clientId, address));
    }

    /**
     * Whether a client may withdraw to an address.
     *
     * <p>Normalises before comparing, because the caller may be holding whatever spelling arrived
     * over HTTP while the stored side is always lower case.
     *
     * @param clientId whose list to search
     * @param address the destination, in any case
     * @return true if it is listed
     */
    @Transactional(readOnly = true)
    public boolean isAllowed(UUID clientId, String address) {
        return whitelist.existsByClientIdAndAddress(clientId, EthereumAddress.normalise(address));
    }
}
