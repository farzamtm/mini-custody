package com.farzam.custody.withdrawal;

import com.farzam.custody.api.model.WithdrawalDto;
import com.farzam.custody.api.model.WithdrawalStatusDto;
import java.time.ZoneOffset;

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

    static WithdrawalDto of(Withdrawal withdrawal) {
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
                .failureReason(withdrawal.getFailureReason());
    }
}
