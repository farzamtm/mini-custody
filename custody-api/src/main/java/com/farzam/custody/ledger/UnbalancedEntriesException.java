package com.farzam.custody.ledger;

import java.io.Serial;

/**
 * A journal transaction whose entries do not sum to zero, or that has fewer than two of them.
 *
 * <p>Unchecked, like every domain exception in this project: checked exceptions force
 * {@code throws} clauses through layers that cannot do anything about the failure, and — the
 * reason that actually matters here — Spring only rolls a transaction back on an unchecked
 * exception by default.
 *
 * <p>This one is a programming error rather than a user error. It means the caller built the wrong
 * entries, so it maps to a 500, not a 4xx.
 */
public class UnbalancedEntriesException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnbalancedEntriesException(String message) {
        super(message);
    }
}
