package com.farzam.custody.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * M2 acceptance test for the state machine.
 *
 * <p>No Spring and no database: the transition table is pure logic, so it is testable in
 * microseconds, and there is no excuse for it to be tested any other way.
 */
class WithdrawalStatusTest {

    @Test
    void theHappyPathIsTheOnlyWayForward() {
        assertThat(WithdrawalStatus.PENDING_APPROVAL.canMoveTo(WithdrawalStatus.APPROVED)).isTrue();
        assertThat(WithdrawalStatus.APPROVED.canMoveTo(WithdrawalStatus.BROADCAST)).isTrue();
        assertThat(WithdrawalStatus.BROADCAST.canMoveTo(WithdrawalStatus.CONFIRMED)).isTrue();
    }

    @Test
    void aWithdrawalCanBeTurnedDownBeforeItIsSignedAndFailAfter() {
        assertThat(WithdrawalStatus.PENDING_APPROVAL.canMoveTo(WithdrawalStatus.REJECTED)).isTrue();
        assertThat(WithdrawalStatus.APPROVED.canMoveTo(WithdrawalStatus.FAILED)).isTrue();
        assertThat(WithdrawalStatus.BROADCAST.canMoveTo(WithdrawalStatus.FAILED)).isTrue();

        // REJECTED means "we never sent it", so it is not available once the signer has been asked.
        assertThat(WithdrawalStatus.APPROVED.canMoveTo(WithdrawalStatus.REJECTED)).isFalse();
        assertThat(WithdrawalStatus.BROADCAST.canMoveTo(WithdrawalStatus.REJECTED)).isFalse();
    }

    @Test
    void confirmedCannotGoBackToApproved() {
        // The M2 acceptance criterion, and the one that matters most: a transaction that is on chain
        // with three confirmations cannot be un-sent, so no state machine may pretend otherwise.
        assertThat(WithdrawalStatus.CONFIRMED.canMoveTo(WithdrawalStatus.APPROVED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = WithdrawalStatus.class, names = {"CONFIRMED", "REJECTED", "FAILED"})
    void aTerminalStatusGoesNowhereAtAll(WithdrawalStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (WithdrawalStatus next : WithdrawalStatus.values()) {
            assertThat(terminal.canMoveTo(next)).as("%s → %s", terminal, next).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = WithdrawalStatus.class, names = {"PENDING_APPROVAL", "APPROVED", "BROADCAST"})
    void anInFlightStatusIsNotTerminal(WithdrawalStatus inFlight) {
        assertThat(inFlight.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(WithdrawalStatus.class)
    void nothingIsAllowedToMoveToItself(WithdrawalStatus status) {
        assertThat(status.canMoveTo(status)).isFalse();
    }

    @Test
    void onlyTheTwoUnsuccessfulEndingsReleaseTheHold() {
        Set<WithdrawalStatus> releasing = EnumSet.allOf(WithdrawalStatus.class)
                .stream()
                .filter(WithdrawalStatus::releasesTheHold)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(WithdrawalStatus.class)));

        // CONFIRMED is terminal too, but it settles the hold against EXTERNAL instead of giving it
        // back — the money really did leave. Confusing the two would credit a client for funds that
        // are on somebody else's address.
        assertThat(releasing).containsExactlyInAnyOrder(WithdrawalStatus.REJECTED, WithdrawalStatus.FAILED);
    }
}
