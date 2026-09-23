package com.farzam.custody.withdrawal;

import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything needed to request a withdrawal, in the domain's own types.
 *
 * <p>A separate type from the generated {@code WithdrawalRequestDto} on purpose. The DTO is the
 * shape of the contract and carries the amount as a {@code String}, because JSON numbers lose
 * precision above 2^53; the domain wants a {@link BigInteger}. Translating once, at the controller,
 * means nothing below it has to know that wire amounts are strings, and a change to the contract
 * cannot ripple past the controller without a compile error.
 *
 * @param accountId the client account to debit
 * @param destination where the funds are going, in any case
 * @param amountWei how much, in wei
 * @param idempotencyKey the client's key for this request, from the {@code Idempotency-Key} header
 */
public record WithdrawalCommand(UUID accountId, String destination, BigInteger amountWei, String idempotencyKey) {

    public WithdrawalCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amountWei, "amountWei");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
