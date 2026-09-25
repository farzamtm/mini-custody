package com.farzam.signer.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalBroadcast;
import com.farzam.signer.support.AbstractSignerTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the relay does when the broker is not there.
 *
 * <p>The answer has to be "nothing, quietly, and try again later", because that is the whole point
 * of an outbox: a broker being unreachable is a delay rather than a lost event. The row keeps its
 * null {@code published_at} and the next tick picks it up.
 *
 * <p>The case worth having a test for is narrower and was a real bug in custody-api's relay. Before
 * the producer can choose a partition it needs the topic's metadata, and when no broker is reachable
 * {@code send} throws on the calling thread instead of returning a failed future — so catching only
 * the future's failure catches nothing, the exception escapes the batch loop, the transaction rolls
 * back, and every row the same batch had <em>already</em> published is un-marked and sent again.
 * One unreachable broker becomes a pile of duplicate events.
 */
@SpringBootTest
class OutboxRelayFailureTest extends AbstractSignerTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ResultSigningKey signingKey;

    @Test
    void aRowThatCannotBeSentStaysUnpublishedAndDoesNotBlowUpTheBatch() {
        UUID withdrawalId = UUID.randomUUID();
        var transactions = new TransactionTemplate(transactionManager);

        UUID eventId = transactions.execute(
                status -> writer.append(
                        Topics.SIGNER_RESULTS,
                        EventType.WITHDRAWAL_BROADCAST,
                        withdrawalId,
                        new WithdrawalBroadcast(withdrawalId, "0x" + "deadbeef".repeat(8))));

        OutboxRelay relay = relayPointedAtNothing();
        // No exception, and nothing claimed as sent.
        int published = transactions.execute(status -> relay.publishBatch());
        assertThat(published).isZero();

        assertThat(isStillUnpublished(eventId)).isTrue();
    }

    /**
     * A relay whose producer has nowhere to go.
     *
     * <p>Port 1 rather than a stopped container: it refuses connections immediately and
     * deterministically, so the test takes as long as the timeouts below and not as long as a TCP
     * connect timeout.
     */
    private OutboxRelay relayPointedAtNothing() {
        var producers = new DefaultKafkaProducerFactory<String, String>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        "127.0.0.1:1",
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.MAX_BLOCK_MS_CONFIG,
                        500,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                        1000,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        500));
        return new OutboxRelay(jdbc, new KafkaTemplate<>(producers), signingKey, 10, Duration.ofSeconds(2));
    }

    private boolean isStillUnpublished(UUID eventId) {
        return jdbc.sql("select count(*) from outbox where id = :id and published_at is null")
                .param("id", eventId)
                .query(Long.class)
                .single() == 1L;
    }
}
