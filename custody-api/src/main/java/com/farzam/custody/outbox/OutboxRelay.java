package com.farzam.custody.outbox;

import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * <p>The reading half of the transactional outbox. {@link OutboxWriter} has already guaranteed that
 * a row exists if and only if the state change it describes was committed; this class's only job is
 * to get each row to the broker at least once.
 *
 * <p><b>{@code FOR UPDATE SKIP LOCKED} is what makes this safe to run more than once.</b> Two
 * instances of custody-api both tick every 500 ms. Plain {@code FOR UPDATE} would make the second
 * one block on the first one's rows and then publish them again once the lock was released, which
 * turns a scaled-out service into a duplicate generator. {@code SKIP LOCKED} tells Postgres to step
 * over any row another transaction already holds, so each instance takes a disjoint batch, nobody
 * waits, and nothing is published twice by two relays at the same moment.
 *
 * <p><b>Delivery is at-least-once, by construction.</b> Between the broker's acknowledgement and the
 * {@code UPDATE} that marks the row there is a window, and a crash inside it leaves a published row
 * that still looks unpublished. The next tick sends it again. That window cannot be closed — it is
 * the dual-write problem again, one layer down, and the honest response is not to pretend otherwise
 * but to make every consumer idempotent, which is what {@code processed_events} is for.
 *
 * @see OutboxWriter the writing half
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * The claim.
     *
     * <p>{@code order by created_at, id} rather than {@code created_at} alone: {@code created_at}
     * defaults to {@code now()}, which in Postgres is the start of the <em>transaction</em>, so two
     * events written by concurrent transactions can share a timestamp to the microsecond. Without a
     * tiebreaker the order of a batch would be whatever the executor felt like, and two events about
     * one withdrawal could leave in the wrong order.
     *
     * <p>{@code cast(payload as text)} because the driver hands back a {@code jsonb} column as a
     * driver-specific object otherwise; the relay wants the text, which it parses once into a tree.
     *
     * <p>The {@code where published_at is null} matches the partial index {@code outbox_unpublished}
     * exactly, so this query reads an index containing only the backlog — a handful of rows —
     * however many million published rows the table has accumulated behind it.
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

    private static final RowMapper<OutboxRow> AS_ROW = (rs, rowNum) -> new OutboxRow(
            rs.getObject("id", UUID.class),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("topic"),
            EventType.ofWireName(rs.getString("event_type")),
            EventJson.read(rs.getString("payload"), JsonNode.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final Duration ackTimeout;

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka,
            @Value("${outbox.relay.batch-size:100}") int batchSize,
            @Value("${outbox.relay.ack-timeout:5s}") Duration ackTimeout) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Publishes one batch of unpublished rows.
     *
     * <p>Public and callable directly, rather than being the {@code @Scheduled} method itself, for
     * two reasons. {@code @Transactional} goes through a proxy, and a scheduled method calling a
     * transactional method on {@code this} would never reach it — the trap {@code JournalWriter}
     * documents. And a test that wants to know what one batch does should not have to wait for a
     * timer to decide; {@link OutboxScheduling} is a separate bean precisely so it can be switched
     * off.
     *
     * <p><b>The network call happens while the rows are locked.</b> That is a real cost — a database
     * transaction is held open for the round trip to the broker — and the alternative is to claim the
     * rows in one transaction and send in another. It is not taken here: the claim-then-send split
     * widens the crash window between "this row is mine" and "this row is sent", so it trades a
     * shorter lock for more duplicates, and the lock is short because {@code SKIP LOCKED} means
     * nobody is queueing behind it. ADR 0005 has the full argument.
     *
     * <p><b>A failure stops the batch rather than skipping the row.</b> Kafka only orders messages
     * within a partition, and the ordering that matters here — everything about one withdrawal, in
     * sequence — is bought by keying on the withdrawal id. Skipping a row that would not send and
     * carrying on would publish a later event about the same withdrawal before an earlier one. The
     * price is head-of-line blocking: a single permanently unsendable row halts the relay. In
     * practice a send fails because the broker is unreachable, in which case the next row was not
     * going anywhere either.
     *
     * @return how many rows were published
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "Two ints and a UUID. None can carry a newline, so no producer of an "
                    + "outbox row can forge a log line through them.")
    @Transactional
    public int publishBatch() {
        List<OutboxRow> rows = jdbc.sql(CLAIM_BATCH).param("batchSize", batchSize).query(AS_ROW).list();

        int published = 0;
        for (OutboxRow row : rows) {
            try {
                publish(row);
            } catch (OutboxPublishException failure) {
                // Not an error log: the rows stay unpublished and the next tick retries them, which
                // is the outbox working as designed rather than failing.
                LOG.warn(
                        "outbox relay stopped after {} of {} rows; {} could not be sent",
                        published,
                        rows.size(),
                        failure.eventId(),
                        failure);
                break;
            }
            markPublished(row.id());
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
     * <p>The wait is the point. {@code send} is asynchronous, so without blocking on the returned
     * future the relay would mark rows published on the strength of having queued them in the
     * producer's buffer, and a broker that rejected every one of them would leave a table full of
     * rows marked sent. With {@code acks=all} the future completes only once every in-sync replica
     * has the message, so a row is marked published only when the message genuinely cannot be lost
     * by the broker.
     */
    private void publish(OutboxRow row) {
        String message = EventJson.write(row.toEnvelope());
        try {
            // The key is the withdrawal id: one withdrawal's events hash to one partition and are
            // therefore delivered in the order they were written. Without a key Kafka would
            // round-robin them and "broadcast" could arrive before "approved".
            kafka.send(row.topic(), row.aggregateId().toString(), message)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // Restore the flag before leaving: swallowing it would hide a shutdown request from
            // everything further up the stack, including the scheduler trying to stop.
            Thread.currentThread().interrupt();
            throw new OutboxPublishException(row.id(), "interrupted while waiting for the broker", interrupted);
        } catch (ExecutionException | TimeoutException | KafkaException failure) {
            // KafkaException belongs in this list even though `send` is asynchronous, and it is the
            // easy one to leave out. Before the producer can choose a partition it has to fetch the
            // topic's metadata, and when it cannot — no broker reachable, topic unknown — `send`
            // throws on the calling thread rather than returning a future that will fail, so
            // catching only the future's failure catches nothing. It is Spring's KafkaException
            // rather than Apache's: KafkaTemplate wraps whatever the producer raised.
            //
            // Without this the exception would escape the batch loop, roll the transaction back, and
            // un-mark every row the same batch had already published successfully — turning one
            // unreachable broker into a pile of duplicate events.
            throw new OutboxPublishException(row.id(), "the broker did not acknowledge the event", failure);
        }
    }

    private void markPublished(UUID eventId) {
        jdbc.sql(MARK_PUBLISHED).param("id", eventId).update();
    }
}
