package com.farzam.custody.ledger;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credits a client account as an arriving deposit would.
 *
 * <p>In the finished system this is what the chain watcher does when it sees funds arrive at the hot
 * wallet. Until that exists, {@code POST /dev/deposits} is the only way to get money into the ledger,
 * so the withdrawal flow has something to spend — which is why it lives in the main source tree
 * rather than in a test helper, and why the controller in front of it is restricted to the
 * {@code dev} profile.
 */
@Component
class DepositWriter {

    private final AccountRepository accounts;
    private final LedgerService ledger;

    DepositWriter(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * Books {@code EXTERNAL −amount / CLIENT +amount}, opening the client's account if needed.
     *
     * <p>Opening the account here is what makes a client exist: there is no separate registration
     * step and no {@code clients} table, because a client in this system is precisely a UUID with a
     * balance. The real one would have onboarding, KYC and an identity long before any money moved.
     *
     * @param clientId whose account to credit
     * @param amountWei how much, in wei
     * @return the account's id — not the account, whose cached balance this method has just made
     *     stale; see {@link DepositService#deposit}
     */
    @Transactional
    public UUID credit(UUID clientId, BigInteger amountWei) {
        // saveAndFlush, not save, and the difference is the whole deposit. JPA queues an INSERT in
        // the persistence context and writes it at commit; the ledger moves balances with JdbcClient
        // and never looks at that context. Without the flush, the `select … for update` a few lines
        // below runs against a table where this account does not exist yet and the posting fails with
        // UnknownAccountException. Auto-flush would have covered a JPQL query, but not raw SQL —
        // exactly the seam ADR 0002 warns about.
        Account account = accounts.findByClientIdAndType(clientId, AccountType.CLIENT)
                .orElseGet(() -> accounts.saveAndFlush(Account.forClient(clientId)));

        // A random reference id, so every call is a new deposit. Real deposits would use the
        // transaction hash and the log index, which is what makes replaying the chain safe.
        ledger.post(
                JournalKind.DEPOSIT,
                UUID.randomUUID(),
                List.of(new Entry(SystemAccounts.EXTERNAL, amountWei.negate()), new Entry(account.getId(), amountWei)));

        return account.getId();
    }
}
