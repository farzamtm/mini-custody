package com.farzam.signer.messaging;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which events this service has already acted on.
 *
 * <p>The same guard custody-api has, and it carries more weight here. A duplicate applied on the
 * custody side books a ledger entry twice; a duplicate applied here signs a second transaction, with
 * a second nonce, paying the same person again — and both would be mined. The signing log's primary
 * key is the backstop for that, but this is the layer that stops the work from being attempted at
 * all.
 *
 * <p>Kafka gives at-least-once delivery and no setting changes it: a consumer is redelivered a
 * message whenever it crashes between handling it and committing the offset, whenever a rebalance
 * moves its partition mid-flight, and whenever a relay resends a row it had already published. The
 * event id goes into {@code processed_events} in the same transaction as the work it caused, so
 * there is no state in which one happened and the other did not.
 */
@Component
public class ProcessedEvents {

    /**
     * {@code on conflict do nothing}, and not a catch.
     *
     * <p>Catching the primary-key violation does not work in Postgres: a constraint violation aborts
     * the whole transaction, so every statement after the catch fails with "current transaction is
     * aborted". Asking the database not to raise it is the only version that leaves a transaction
     * the caller can still use.
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
     * Claims an event, if nobody has claimed it before.
     *
     * <p>{@link Propagation#MANDATORY}: committed on its own this would be a promise that the work
     * was done, made before it was attempted, and a crash straight afterwards would lose the event
     * for good — every redelivery would then be recognised as a duplicate and dropped.
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
