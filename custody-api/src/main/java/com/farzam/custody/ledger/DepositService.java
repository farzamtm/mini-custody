package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Seeds a client balance, for the dev profile's simulated deposits. */
@Service
public class DepositService {

    private final DepositWriter writer;
    private final AccountRepository accounts;

    DepositService(DepositWriter writer, AccountRepository accounts) {
        this.writer = writer;
        this.accounts = accounts;
    }

    /**
     * Credits a client and returns the account as it now stands.
     *
     * <p>Two calls, deliberately, and it would be a bug to fold them into one transactional method.
     * The ledger moves balances with SQL and does not write through Hibernate — ADR 0002 — so inside
     * the posting's transaction the {@link Account} instance in the persistence context still holds
     * the balance it was loaded with. Returning it there would report the balance from before the
     * deposit, and nothing would look wrong.
     *
     * <p>Reading afterwards, outside that transaction, gets a fresh persistence context and therefore
     * the number Postgres actually holds. The read is its own transaction because
     * {@code spring.jpa.open-in-view} is off and {@code SimpleJpaRepository} opens one per call.
     *
     * @param clientId whose account to credit
     * @param amountWei how much, in wei
     * @return the account, with the balance the deposit produced
     */
    public Account deposit(UUID clientId, BigInteger amountWei) {
        UUID accountId = writer.credit(clientId, amountWei);
        return accounts.findById(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
    }
}
