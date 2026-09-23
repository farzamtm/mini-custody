package com.farzam.custody.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to accounts, and the only place that creates them.
 *
 * <p>Spring Data generates the implementation from the interface: the method name below <em>is</em>
 * the query. Same idea as a Doctrine repository, minus the class.
 *
 * <p>There is no method here that writes a balance, and that is deliberate. Balances move only
 * through {@link LedgerService#post}, where the double-entry rule and the row locking are.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** "This client's ETH account" — one row, since a client has one account per type. */
    Optional<Account> findByClientIdAndType(UUID clientId, AccountType type);
}
