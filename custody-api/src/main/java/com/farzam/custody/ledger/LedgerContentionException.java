package com.farzam.custody.ledger;

import java.io.Serial;
import java.util.UUID;

/**
 * Optimistic locking lost the race too many times in a row.
 *
 * <p>Only reachable with {@code ledger.locking=optimistic}. Under pessimistic locking a contending
 * writer waits rather than failing, so there is nothing to retry and nothing to give up on — which
 * is most of the argument in
 * <a href="../../../../../../../../docs/adr/0001-pessimistic-row-locks-for-ledger-balances.md">ADR
 * 0001</a>.
 *
 * <p>The right HTTP answer is {@code 503} with a {@code Retry-After}, not a 500: nothing is wrong,
 * the account is simply busier than the retry budget allows.
 */
public class LedgerContentionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LedgerContentionException(JournalKind kind, UUID referenceId, int attempts, Throwable cause) {
        super("gave up booking %s/%s after %d attempts".formatted(kind, referenceId, attempts), cause);
    }
}
