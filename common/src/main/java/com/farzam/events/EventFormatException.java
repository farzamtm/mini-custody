package com.farzam.events;

import java.io.Serial;

/**
 * The bytes on the topic are not an event this system can read.
 *
 * <p>Unchecked on purpose. Serialising an event the code just built is not an operation a caller can
 * meaningfully recover from — a checked exception there buys a {@code try} block that rethrows — and
 * on the reading side the recovery is not local either: it belongs to the Kafka error handler, which
 * catches this by type and sends the message straight to the dead-letter topic without retrying.
 *
 * <p>That "without retrying" is the point of having a distinct type at all. A broker timeout is
 * worth three attempts because the next one may work; invalid JSON is not, because the bytes will be
 * exactly as invalid in two seconds' time. Retrying a poison message only delays the moment somebody
 * looks at it, and blocks the partition while it does.
 */
public class EventFormatException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what was wrong with the event
     */
    public EventFormatException(String message) {
        super(message);
    }

    /**
     * @param message what was wrong with the event
     * @param cause the parser's own complaint
     */
    public EventFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
