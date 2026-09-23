package com.farzam.custody.ledger;

import java.io.Serial;
import java.math.BigInteger;
import java.util.UUID;

/**
 * An account that may not go negative was asked to.
 *
 * <p>This is the expected outcome of a client trying to withdraw more than they hold, so it is a
 * business result rather than a fault: M2 maps it to {@code 422} with code
 * {@code INSUFFICIENT_FUNDS}.
 *
 * <p>It is thrown while the row is locked and the balance has been read, so the numbers it carries
 * are the ones the decision was actually made on.
 */
public class InsufficientFundsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID accountId;

    public InsufficientFundsException(UUID accountId, BigInteger balance, BigInteger requested) {
        super("account %s holds %s wei, cannot release %s wei".formatted(accountId, balance, requested));
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
