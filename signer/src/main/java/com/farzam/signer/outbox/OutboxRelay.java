package com.farzam.signer.outbox;

import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventSignature;
import com.farzam.events.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed outbox rows onto Kafka, and marks them published.
 *
 * <p>The reading half of the signer's transactional outbox, and a deliberate copy of custody-api's —
 * see {@link OutboxWriter} and ADR 0009 for why there are two. The three properties that make it
 * correct are worth restating rather than cross-referencing, because they are the ones a future edit
 * could quietly remove:
 *
 * <p><b>{@code FOR UPDATE SKIP LOCKED} is what makes more than one instance safe.</b> Plain
 * {@code FOR UPDATE} would make a second signer block on the first one's rows and then publish them
 * again once the lock was released. {@code SKIP LOCKED} steps over rows another transaction holds,
 * so each instance takes a disjoint batch and nothing is published twice.
 *
 * <p><b>Delivery is at-least-once by construction.</b> Between the broker's acknowledgement and the
 * {@code UPDATE} there is a window, and a crash inside it resends the event. That window cannot be
 * closed; custody-api's {@code processed_events} is what makes it harmless.
 *
 * <p><b>A failed send stops the batch rather than skipping the row.</b> Ordering per withdrawal is
 * bought by keying on the withdrawal id, and stepping over an unsendable row could publish a later
 * event about the same withdrawal before an earlier one.
 *
 * <p><b>Every message carries an Ed25519 signature over its own bytes.</b> This is the one place the
 * signer's copy of the relay does something custody-api's does not, and it is why ADR 0009's
 * duplication is now load-bearing rather than merely tolerated. custody-api refuses a result it
 * cannot verify, because believing an unauthenticated "the signer refused" releases a ledger hold
 * for a withdrawal that is being broadcast at that moment. See {@link ResultSigningKey} and ADR 0012.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * {@code order by created_at, id} rather than {@code created_at} alone: the column defaults to
     * {@code now()}, which in Postgres is the start of the transaction, so two rows written
     * concurrently can share a timestamp to the microsecond. Without the tiebreaker two events about
     * one withdrawal could leave in the wrong order.
     *
     * <p>{@code cast(payload as text)} because the driver otherwise hands back {@code jsonb} as a
     * driver-specific object. The {@code where} matches the partial index exactly, so the query
     * reads an index holding only the backlog.
     */
    private static final String CLAIM_BATCH = """
            select id, aggregate_id, topic, event_type, cast(payload as text) as payload, created_at
            from outbox
            where published_at is null
            order by created_at, id
            limit :batchSize
            for update skip locked
            """;

    private static final String MARK_PUBLISHED = """
            update outbox set published_at = now() where id = :id
            """;

    private static final RowMapper<Row> AS_ROW = (rs, rowNum) -> new Row(
            rs.getObject("id", UUID.class),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("topic"),
            EventType.ofWireName(rs.getString("event_type")),
            EventJson.read(rs.getString("payload"), JsonNode.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final ResultSigningKey signingKey;
    private final int batchSize;
    private final Duration ackTimeout;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, ResultSigningKey signingKey,
            @Value("${outbox.relay.batch-size:100}") int batchSize,
            @Value("${outbox.relay.ack-timeout:5s}") Duration ackTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.signingKey = signingKey;
        this.batchSize = batchSize;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Publishes one batch of unpublished rows.
     *
     * <p>Public and callable directly rather than being the scheduled method itself, because
     * {@code @Transactional} goes through a proxy: a scheduled method calling this on {@code this}
     * would run the whole batch outside any transaction, locking nothing. {@link OutboxScheduling}
     * is a separate bean so a test can switch the timer off and call this instead of sleeping.
     *
     * @return how many rows were published
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "Two ints and a UUID. None can carry a newline, so nothing that writes "
                    + "an outbox row can forge a log line through them.")
    @Transactional
    public int publishBatch() {
        List<Row> rows = jdbc.sql(CLAIM_BATCH).param("batchSize", batchSize).query(AS_ROW).list();

        int published = 0;
        for (Row row : rows) {
            try {
                publish(row);
            } catch (PublishFailed failure) {
                // Not an error log: the rows keep their null published_at and the next tick retries
                // them, which is the outbox working rather than failing.
                LOG.warn(
                        "outbox relay stopped after {} of {} rows; {} could not be sent",
                        published,
                        rows.size(),
                        failure.eventId,
                        failure);
                break;
            }
            jdbc.sql(MARK_PUBLISHED).param("id", row.id()).update();
            published++;
        }

        if (published > 0) {
            LOG.debug("outbox relay published {} of {} claimed rows", published, rows.size());
        }
        return published;
    }

    /**
     * Sends one row and waits for the broker to confirm it.
     *
     * <p>The wait is the point: {@code send} is asynchronous, so without blocking on the future the
     * relay would mark rows published on the strength of having put them in the producer's buffer.
     * With {@code acks=all} the future completes only once the message cannot be lost by the broker.
     *
     * <p><b>The signature is computed here, over the bytes that are actually sent.</b> Signing
     * earlier — in {@link OutboxWriter}, against the payload — would sign something other than what
     * leaves the process, and the gap between the two is where a bug would live. Signing the
     * serialised envelope means the header covers the event id, the type, the timestamp, the
     * aggregate id and the payload together, so none of them can be edited in flight. Because every
     * field of the envelope comes from the row rather than from the clock, a republished row
     * produces the same bytes and therefore the same signature, and the consumer's duplicate check
     * behaves exactly as it did before M7.
     */
    private void publish(Row row) {
        String message = EventJson.write(row.toEnvelope());
        var record = new ProducerRecord<>(row.topic(), null, row.aggregateId().toString(), message);
        record.headers().add(EventSignature.HEADER, signingKey.sign(message).getBytes(StandardCharsets.UTF_8));
        try {
            // The key is the withdrawal id, so one withdrawal's events hash to one partition and
            // arrive in the order they were written.
            kafka.send(record).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Restore the flag: swallowing it would hide a shutdown request from the scheduler.
            Thread.currentThread().interrupt();
            throw new PublishFailed(row.id(), "interrupted while waiting for the broker", interrupted);
        } catch (ExecutionException | TimeoutException | KafkaException failure) {
            // KafkaException belongs here even though `send` is asynchronous, and it is the easy one
            // to leave out. The producer needs topic metadata before it can choose a partition, and
            // when no broker is reachable `send` throws on the calling thread rather than returning
            // a failed future — so catching only the future catches nothing, the exception escapes
            // the loop, the transaction rolls back, and every row this batch had already published
            // is un-marked and will be published again.
            throw new PublishFailed(row.id(), "the broker did not acknowledge the event", failure);
        }
    }

    /**
     * One unpublished row, as the relay reads it.
     *
     * <p>Every field of the envelope comes from the row and nothing is generated at send time, which
     * is what makes republishing an already-sent row produce a byte-identical message rather than a
     * second, differently-identified event.
     */
    private record Row(UUID id, UUID aggregateId, String topic, EventType eventType, JsonNode payload,
            Instant createdAt) {

        EventEnvelope toEnvelope() {
            return new EventEnvelope(id, eventType, createdAt, aggregateId, payload);
        }
    }

    /**
     * A row could not be handed to the broker.
     *
     * <p>Not an error: the row keeps its null {@code published_at} and the next tick tries again.
     * A distinct type so the loop can catch exactly this and stop the batch, while anything else —
     * a row that will not parse, a bug in the envelope — propagates and rolls the transaction back.
     */
    private static final class PublishFailed extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final UUID eventId;

        private PublishFailed(UUID eventId, String message, Throwable cause) {
            super(message, cause);
            this.eventId = eventId;
        }
    }
}
