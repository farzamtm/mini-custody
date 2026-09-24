package com.farzam.custody.withdrawal;

import com.farzam.custody.api.model.WithdrawalDto;
import com.farzam.custody.api.model.WithdrawalStatusDto;
import java.time.ZoneOffset;
import java.util.OptionalInt;

/**
 * Turns a {@link Withdrawal} into what the contract promises.
 *
 * <p>Note what does not cross: {@code idempotencyKey}, {@code requestHash} and {@code version} are
 * on the entity and not on the DTO. The first two are the server's record of how this request was
 * decided, and echoing them back would let anyone holding a withdrawal id learn the key that
 * controls it. The version is a locking detail with no meaning to a client. A response assembled by
 * reflection over the entity would have published all three.
 */
final class WithdrawalDtos {

    private WithdrawalDtos() {}

    /**
     * For the responses that have no confirmation count to hand.
     *
     * <p>{@code POST /v1/withdrawals} returns a withdrawal that was created a microsecond ago and
     * has no transaction, never mind a receipt — asking for its depth would be a query guaranteed to
     * return nothing.
     */
    static WithdrawalDto of(Withdrawal withdrawal) {
        return of(withdrawal, OptionalInt.empty());
    }

    static WithdrawalDto of(Withdrawal withdrawal, OptionalInt confirmations) {
        return new WithdrawalDto(
                withdrawal.getId(),
                withdrawal.getClientId(),
                withdrawal.getAccountId(),
                withdrawal.getDestination(),
                withdrawal.getAmount().toString(),
                WithdrawalStatusDto.fromValue(withdrawal.getStatus().name()),
                // The entity stores an Instant — a moment, with no opinion about where on
                // earth anyone was. The contract says date-time, so it needs an offset, and
                // UTC is the only one that is not a guess.
                withdrawal.getCreatedAt().atOffset(ZoneOffset.UTC),
                withdrawal.getUpdatedAt().atOffset(ZoneOffset.UTC)).txHash(withdrawal.getTxHash())
                .failureReason(withdrawal.getFailureReason())
                // Null rather than zero when the chain has no receipt. Zero would say "mined, and
                // nothing on top of it", which is a different and briefly-true state; absent says
                // the chain has not been heard from about this transaction at all.
                .confirmations(confirmations.isPresent() ? confirmations.getAsInt() : null);
    }
}
