package com.farzam.custody.outbox;

import java.io.Serial;
import java.util.UUID;

/**
 * A row could not be handed to the broker.
 *
 * <p>Not an error, in the sense that nothing is lost and nobody needs to be woken up: the row keeps
 * its null {@code published_at}, and the next tick of the relay tries again. The outbox exists
 * precisely so that a broker being unreachable is a delay rather than a missing event.
 *
 * <p>It is a distinct type rather than a bare runtime exception so the relay can catch exactly this
 * and stop the batch, while anything else — a bug in the envelope, a row that will not parse — is
 * left to propagate and roll the transaction back.
 */
class OutboxPublishException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID eventId;

    OutboxPublishException(UUID eventId, String message, Throwable cause) {
        super(message, cause);
        this.eventId = eventId;
    }

    /** @return the event that did not make it out */
    UUID eventId() {
        return eventId;
    }
}
