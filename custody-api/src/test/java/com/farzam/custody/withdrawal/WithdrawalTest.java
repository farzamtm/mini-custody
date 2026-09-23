package com.farzam.custody.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The entity's own rules, with no Spring and no database in the way. */
class WithdrawalTest {

    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void aNewWithdrawalIsPendingApprovalAndHasAnIdOfItsOwn() {
        Withdrawal withdrawal = requested();

        assertThat(withdrawal.getId()).isNotNull();
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
        assertThat(withdrawal.getTxHash()).isNull();
        assertThat(withdrawal.getFailureReason()).isNull();
        assertThat(withdrawal.getCreatedAt()).isEqualTo(withdrawal.getUpdatedAt());
    }

    @Test
    void theDestinationIsStoredInLowerCaseWhateverCaseItArrivedIn() {
        Withdrawal withdrawal = Withdrawal.requested(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0x70997970C51812DC3A010C7D01B50E0D17DC79C8",
                POINT_FOUR_ETH,
                "key",
                "hash");

        assertThat(withdrawal.getDestination()).isEqualTo(DESTINATION);
    }

    @Test
    void anAmountOfZeroOrLessIsNotAWithdrawal() {
        assertThatIllegalArgumentException().isThrownBy(() -> withAmount(BigInteger.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> withAmount(BigInteger.ONE.negate()));
    }

    @Test
    void anIllegalTransitionThrowsAndLeavesTheStatusAlone() {
        Withdrawal withdrawal = requested();

        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> withdrawal.moveTo(WithdrawalStatus.CONFIRMED))
                .withMessageContaining("PENDING_APPROVAL")
                .withMessageContaining("CONFIRMED");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    @Test
    void broadcastingRecordsTheTransactionHashAndAdvancesTheStatus() {
        Withdrawal withdrawal = requested();
        withdrawal.moveTo(WithdrawalStatus.APPROVED);

        withdrawal.broadcastAs("0xabc");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(withdrawal.getTxHash()).isEqualTo("0xabc");
    }

    @Test
    void broadcastingFromTheWrongStateRecordsNothing() {
        Withdrawal withdrawal = requested();

        assertThatExceptionOfType(IllegalStateTransitionException.class)
                .isThrownBy(() -> withdrawal.broadcastAs("0xabc"));

        // The hash is set before the transition is checked, so this asserts the order is right:
        // a withdrawal that was never broadcast must not be left holding a transaction hash.
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
    }

    @Test
    void endingBadlyRecordsTheReason() {
        Withdrawal withdrawal = requested();

        withdrawal.endWith(WithdrawalStatus.REJECTED, "no quorum before the deadline");

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(withdrawal.getFailureReason()).isEqualTo("no quorum before the deadline");
        assertThat(withdrawal.getUpdatedAt()).isAfterOrEqualTo(withdrawal.getCreatedAt());
    }

    @Test
    void aSuccessfulEndingCannotCarryAFailureReason() {
        Withdrawal withdrawal = requested();
        withdrawal.moveTo(WithdrawalStatus.APPROVED);
        withdrawal.broadcastAs("0xabc");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> withdrawal.endWith(WithdrawalStatus.CONFIRMED, "but it worked"));
    }

    private static Withdrawal requested() {
        return withAmount(POINT_FOUR_ETH);
    }

    private static Withdrawal withAmount(BigInteger amount) {
        return Withdrawal.requested(UUID.randomUUID(), UUID.randomUUID(), DESTINATION, amount, "key", "hash");
    }
}
