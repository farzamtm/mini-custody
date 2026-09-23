package com.farzam.custody.ledger;

import java.io.Serial;
import java.util.UUID;

/**
 * A posting referenced an account id that is not in the ledger.
 *
 * <p>The foreign key on {@code journal_entries.account_id} would catch this too, a few statements
 * later, as an opaque constraint violation. Catching it while selecting the rows to lock means the
 * error names the account.
 */
public class UnknownAccountException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID accountId;

    public UnknownAccountException(UUID accountId) {
        super("no such account: " + accountId);
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
