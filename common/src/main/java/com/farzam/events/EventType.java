package com.farzam.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Every event type that crosses Kafka, and the name it goes by on the wire.
 *
 * <p>The wire name is {@code PascalCase} and the constant is {@code SCREAMING_SNAKE}, which is why
 * {@link #wireName()} exists rather than {@code name()} being used directly. They are two different
 * vocabularies: one belongs to the contract and cannot change without a new topic version, the other
 * belongs to Java and can be renamed in an afternoon. Conflating them would turn a refactor into a
 * breaking protocol change, silently.
 *
 * <p>An enum rather than string constants, because consumers switch on it: a {@code switch}
 * expression over an enum is exhaustive, so adding a fourth event type makes every listener that has
 * not decided what to do with it stop compiling.
 */
public enum EventType {

    /** custody-api → signer: this withdrawal has its quorum, sign it. */
    WITHDRAWAL_APPROVED("WithdrawalApproved"),

    /** signer → custody-api: signed and sent, here is the transaction hash. */
    WITHDRAWAL_BROADCAST("WithdrawalBroadcast"),

    /** signer → custody-api: refused, here is why. The hold goes back to the client. */
    WITHDRAWAL_SIGNING_FAILED("WithdrawalSigningFailed");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * The name this event is published under.
     *
     * @return the contract's name for this type
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * Jackson's way in, and the outbox relay's.
     *
     * <p>An unrecognised name throws rather than deserialising to {@code null}. A consumer that
     * meets an event type it has never heard of should say so loudly and let the message go to the
     * dead-letter topic, where it can be replayed once the version that understands it is deployed.
     * Skipping it quietly would be a silent data loss that nobody discovers until the reconciliation
     * job disagrees with the chain.
     *
     * @param wireName the {@code eventType} field from an envelope
     * @return the matching type
     * @throws EventFormatException if no type has that name
     */
    @JsonCreator
    public static EventType ofWireName(String wireName) {
        for (EventType type : values()) {
            if (type.wireName.equals(wireName)) {
                return type;
            }
        }
        throw new EventFormatException("unknown event type: " + wireName);
    }
}
