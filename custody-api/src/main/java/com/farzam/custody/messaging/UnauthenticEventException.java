package com.farzam.custody.messaging;

import java.io.Serial;

/**
 * A message on a topic this service authenticates did not come from the service that owns it.
 *
 * <p>Distinct from {@code EventFormatException}, which means the bytes are not an event at all.
 * These bytes may parse perfectly — the point is that nobody who is entitled to send them did. The
 * two are worth telling apart on the dead-letter topic: a format failure is almost always a
 * deployment that has drifted, while this one is either a misconfigured key or somebody producing to
 * a topic they should not be able to reach, and only one of those is worth waking a person for.
 *
 * <p>Registered as non-retryable, for the same reason malformed JSON is: a signature that does not
 * verify now will not verify in two seconds, and spending three attempts proving that only delays
 * every legitimate result queued behind it on the same partition.
 */
public class UnauthenticEventException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what could not be authenticated, without quoting the message itself
     */
    public UnauthenticEventException(String message) {
        super(message);
    }
}
