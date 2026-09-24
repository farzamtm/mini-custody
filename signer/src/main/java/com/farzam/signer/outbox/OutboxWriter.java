package com.farzam.signer.outbox;

import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the intent to publish a result, in the caller's transaction.
 *
 * <p>The signer has the same dual-write problem custody-api has, with a worse failure. Sign, commit,
 * then publish, and a crash in the gap leaves a nonce spent, a raw transaction in {@code
 * signing_log}, possibly a payment on chain — and a custody-api that never hears about any of it, so
 * the withdrawal sits in {@code APPROVED} with the client's money held and nothing anywhere that
 * looks like an error. Publish first and custody-api records a transaction hash for a signature that
 * was rolled back.
 *
 * <p>So the result becomes a row written by the transaction that did the signing, and {@link
 * OutboxRelay} turns it into a message afterwards.
 *
 * <p>This is custody-api's {@code OutboxWriter} again, and the fact that there are now two of them
 * is a decision rather than an oversight: ADR 0009 records why they were not extracted into a shared
 * module in this milestone, and what would make that worth doing.
 */
@Component
public class OutboxWriter {

    /**
     * {@code cast(:payload as jsonb)} because Postgres will not implicitly coerce {@code text} to
     * {@code jsonb} in an INSERT, and the parameter arrives as a Java string.
     */
    private static final String INSERT = """
            insert into outbox (id, aggregate_id, topic, event_type, payload)
            values (:id, :aggregateId, :topic, :eventType, cast(:payload as jsonb))
            """;

    private final JdbcClient jdbc;

    OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queues an event as part of whatever transaction is already running.
     *
     * <p>{@link Propagation#MANDATORY} does not start a transaction; it refuses to run without one.
     * Called outside a transaction this throws rather than quietly committing the row on its own —
     * which is exactly the dual write the pattern exists to prevent, and which would look perfectly
     * healthy right up until custody-api acted on a signature that never happened.
     *
     * <p>The returned id is the row's primary key and becomes the envelope's {@code eventId},
     * generated once here rather than per send attempt. That is what lets custody-api's
     * {@code processed_events} recognise a redelivery.
     *
     * @param topic where it goes
     * @param eventType what happened
     * @param aggregateId the withdrawal; becomes the Kafka message key
     * @param payload a payload record from {@code common}, serialised canonically
     * @return the event id
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     transaction to join
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String topic, EventType eventType, UUID aggregateId, Object payload) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", eventId)
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("eventType", eventType.wireName())
                .param("payload", EventJson.write(payload))
                .update();
        return eventId;
    }
}
