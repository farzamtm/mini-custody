package com.farzam.custody.withdrawal;

import com.farzam.custody.ledger.AccountType;
import java.io.Serial;
import java.util.UUID;

/**
 * A withdrawal named an account that exists but is not a client's.
 *
 * <p>Maps to {@code 422} with code {@code NOT_A_CLIENT_ACCOUNT}. Without this check a caller could
 * name {@code PENDING_OUT} or {@code BANK_OPERATING} — real rows with real balances — and withdraw
 * the bank's own funds or somebody else's pending holds. With no authentication on the API yet, the
 * account type is the only thing standing between "can call the endpoint" and "can drain the system
 * accounts", so it is worth its own exception rather than a shrug.
 */
public class NotAClientAccountException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public NotAClientAccountException(UUID accountId, AccountType type) {
        super("account %s is a %s account; only CLIENT accounts can request withdrawals".formatted(accountId, type));
    }
}
