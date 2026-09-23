package com.farzam.custody.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record of which events this service has already acted on.
 *
 * <p>Kafka delivery is at-least-once and there is no configuration that changes that. A consumer is
 * redelivered a message whenever it crashes between handling it and committing the offset, whenever
 * a rebalance moves its partition to somebody else mid-flight, and whenever the outbox relay resends
 * a row it had already published. Duplicates are not an edge case here; they are the normal
 * behaviour of the transport.
 *
 * <p>So the consumer is made idempotent instead: the event id goes into {@code processed_events} in
 * the same transaction as the state change it caused. A redelivery finds the row already there and
 * returns without doing anything. Because both are one transaction, there is no state in which the
 * event was recorded as processed but its effect was not, or the reverse.
 */
@Component
public class ProcessedEvents {

    /**
     * {@code on conflict do nothing}, and not a catch.
     *
     * <p>The obvious alternative — insert, catch the primary-key violation, treat it as a duplicate —
     * does not work in Postgres. A constraint violation aborts the entire transaction, so every
     * statement after the catch fails with "current transaction is aborted"; the handler would have
     * caught the exception and still be unable to do anything except roll back. Asking the database
     * not to raise it in the first place is the only version that leaves a usable transaction.
     */
    private static final String MARK = """
            insert into processed_events (consumer, event_id)
            values (:consumer, :eventId)
            on conflict (consumer, event_id) do nothing
            """;

    private final JdbcClient jdbc;

    ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims an event for this consumer, if nobody has claimed it before.
     *
     * <p>{@link Propagation#MANDATORY}: this is only meaningful inside the transaction that also
     * applies the event. Committed on its own it would be a promise that the work was done, made
     * before the work was attempted — and a crash straight afterwards would lose the event
     * permanently, because every redelivery would now be recognised as a duplicate.
     *
     * <p>The consumer name is a parameter rather than a constant because the table is shared by
     * every consumer in this service. Two consumers that both care about the same event each get to
     * process it once; a single global key space would let whichever ran first silence the other.
     *
     * @param consumer which consumer is asking, matching its Kafka group id
     * @param eventId the envelope's event id
     * @return true if this is the first time, false if it is a redelivery
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     transaction to join
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql(MARK).param("consumer", consumer).param("eventId", eventId).update() == 1;
    }
}
