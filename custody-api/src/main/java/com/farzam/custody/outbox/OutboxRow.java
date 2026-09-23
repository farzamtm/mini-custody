package com.farzam.custody.outbox;

import com.farzam.events.EventEnvelope;
import com.farzam.events.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * One unpublished row, as the relay reads it.
 *
 * <p>Note that this is not an entity and there is no {@code published_at} on it. The relay selects
 * only unpublished rows and marks them by id, so a field that is null by construction would be a
 * field that only ever says the same thing.
 *
 * @param id the row's primary key, which becomes the event id
 * @param aggregateId the withdrawal, which becomes the message key
 * @param topic where it goes
 * @param eventType what happened
 * @param payload the type-specific body, already parsed out of the {@code jsonb} column
 * @param createdAt when the business transaction wrote it, which becomes {@code occurredAt}
 */
record OutboxRow(UUID id, UUID aggregateId, String topic, EventType eventType, JsonNode payload, Instant createdAt) {

    /**
     * Assembles the message this row describes.
     *
     * <p>Every field of the envelope comes from the row; nothing is generated here. That is what
     * makes republishing a row that was already sent produce a byte-identical message rather than a
     * second, differently-identified event — which is the property the consumer's duplicate check
     * depends on.
     *
     * @return the envelope to publish
     */
    EventEnvelope toEnvelope() {
        return new EventEnvelope(id, eventType, createdAt, aggregateId, payload);
    }
}
