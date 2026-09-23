package com.farzam.custody.outbox;

import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the intent to publish an event, in the caller's transaction.
 *
 * <p>This is the whole of the transactional outbox pattern on the writing side, and the interesting
 * part is what it does <em>not</em> do: it does not talk to Kafka. Updating the database and
 * publishing to a broker are two systems with no transaction between them, and doing them in
 * sequence is wrong in both orders. Commit first and a crash before the publish leaves a withdrawal
 * that is APPROVED but that the signer never hears about — stuck, silently, until somebody notices.
 * Publish first and a failed commit leaves the signer signing a withdrawal the database says was
 * never approved, which is money out of the door on the strength of a transaction that did not
 * happen.
 *
 * <p>So the event becomes a row, written by the same transaction as the state change. Either both
 * are there or neither is, and {@link OutboxRelay} turns the row into a message afterwards.
 *
 * @see OutboxRelay the other half
 */
@Component
public class OutboxWriter {

    /**
     * {@code cast(:payload as jsonb)} rather than a Hibernate JSON mapping.
     *
     * <p>The parameter arrives as a Java string, and Postgres will not implicitly coerce {@code text}
     * to {@code jsonb} in an INSERT, so the cast has to be here. Writing it through JPA instead would
     * mean a converter, a dialect-specific type and an argument with Hibernate about a column that is
     * only ever read back as text anyway — ADR 0002's rule applies: the SQL that matters is written
     * as SQL.
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
     * Queues an event for publication as part of whatever transaction is already running.
     *
     * <p>{@link Propagation#MANDATORY} is the load-bearing annotation. It does not start a
     * transaction; it refuses to run without one. Called outside a transaction this throws instead of
     * quietly committing the outbox row on its own, which is exactly the dual write the pattern
     * exists to prevent — and which would otherwise be invisible, because an outbox row written
     * alongside a state change that later rolled back looks perfectly healthy right up until the
     * signer acts on an approval that never happened.
     *
     * <p>The returned id is the row's primary key and becomes the envelope's {@code eventId}. It is
     * generated once, here, in the business transaction — never per send attempt — which is what
     * makes a consumer's {@code processed_events} check able to recognise a redelivery.
     *
     * @param topic where it goes
     * @param eventType what happened
     * @param aggregateId the withdrawal it is about; becomes the Kafka message key
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
