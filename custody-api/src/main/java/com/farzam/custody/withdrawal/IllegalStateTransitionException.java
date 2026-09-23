package com.farzam.custody.withdrawal;

import java.io.Serial;

/**
 * Something tried to move a withdrawal somewhere it cannot go.
 *
 * <p>Maps to {@code 409}: the request conflicts with the state the resource is in, and the client
 * is probably working from a stale copy.
 *
 * <p>This should be unreachable in normal operation, and that is the point of throwing rather than
 * ignoring. Kafka delivers at least once, so an event that has already been applied will arrive
 * again — but the guard against acting on it twice is {@code processed_events} (M4), not this. If
 * this exception fires, two different code paths disagree about what has happened to a withdrawal,
 * which is exactly the kind of thing that must be loud in a system holding other people's money.
 */
public class IllegalStateTransitionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IllegalStateTransitionException(WithdrawalStatus from, WithdrawalStatus to) {
        super("a withdrawal cannot move from %s to %s".formatted(from, to));
    }
}
