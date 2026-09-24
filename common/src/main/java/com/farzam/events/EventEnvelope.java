package com.farzam.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The one shape every message on every topic has.
 *
 * <p>One envelope for all event types, with the type-specific part in {@code payload}, so that a
 * consumer can read who, when and what-kind-of before it commits to a shape — which is what lets it
 * check {@code eventId} against {@code processed_events} and drop a duplicate without ever parsing
 * the payload.
 *
 * <p><b>The envelope is not stored anywhere.</b> It is assembled by the outbox relay from the row it
 * is about to publish: {@code eventId} is the outbox row's primary key and {@code occurredAt} is its
 * {@code created_at}. That identity is load-bearing rather than tidy. The outbox row is written in
 * the same transaction as the state change, so its id is generated exactly once per real business
 * event, however many times the relay goes on to publish it. A relay that minted a fresh id per send
 * attempt would defeat every consumer's duplicate check, because two deliveries of the same event
 * would arrive looking like two events.
 *
 * <p>{@code payload} is a {@link JsonNode} rather than a type parameter because the consumer has to
 * read {@code eventType} before it can know what the payload is; a generic envelope cannot be
 * deserialised without already knowing the answer. Consumers convert it with
 * {@link EventJson#fromNode} once they have switched on the type.
 *
 * @param eventId the outbox row id — the duplicate key every consumer checks
 * @param eventType what happened
 * @param occurredAt when the transaction that caused it committed, near enough
 * @param aggregateId the withdrawal this is about; also the Kafka message key, so everything about
 *     one withdrawal lands on one partition and arrives in order
 * @param payload the type-specific body
 */
public record EventEnvelope(UUID eventId, EventType eventType, Instant occurredAt, UUID aggregateId, JsonNode payload) {

    /**
     * @throws NullPointerException if any field is missing — an envelope with a null {@code eventId}
     *     would be silently un-deduplicatable, which is worse than a failed parse
     */
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Builds an envelope around a payload record.
     *
     * @param eventId the outbox row id
     * @param eventType what happened
     * @param occurredAt the outbox row's creation time
     * @param aggregateId the withdrawal id
     * @param payload a payload record, converted to a tree here
     * @return the envelope, ready to serialise
     */
    public static EventEnvelope of(
            UUID eventId,
            EventType eventType,
            Instant occurredAt,
            UUID aggregateId,
            Object payload) {
        return new EventEnvelope(eventId, eventType, occurredAt, aggregateId, EventJson.toNode(payload));
    }

    /**
     * Reads the payload as the record that goes with this event's type.
     *
     * @param type the payload record class
     * @param <T> that type
     * @return the parsed payload
     * @throws EventFormatException if the payload is not that shape
     */
    public <T> T payloadAs(Class<T> type) {
        return EventJson.fromNode(payload, type);
    }
}
