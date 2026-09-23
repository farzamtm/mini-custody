package com.farzam.custody.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker and one Postgres, shared by every test that needs both.
 *
 * <p>A real broker rather than {@code EmbeddedKafkaBroker}, for the reason {@link
 * AbstractPostgresTest} gives for real Postgres: the behaviour under test is partitioning, keys,
 * consumer groups, offset commits and dead-letter publication, and an in-process stand-in is a
 * second implementation of all of it. The image is the one {@code docker-compose.yml} runs, so the
 * tests and the local stack are the same broker.
 *
 * <p>Started once in a static initialiser and left to Ryuk, again as {@link AbstractPostgresTest}
 * does — a broker per test class would dominate the runtime of the suite.
 *
 * <p>Subclasses deliberately do not carry {@link WithoutKafka}: they run against the same Kafka
 * settings production uses, with only the broker address redirected.
 */
public abstract class AbstractKafkaTest extends AbstractPostgresTest {

    @SuppressWarnings("resource") // stopped by Ryuk at JVM exit, by design
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    /**
     * Points the application at the container's randomly-assigned broker port.
     *
     * @param registry Spring's test property registry
     */
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /**
     * Collects every message on a topic that carries the given key, for a fixed window.
     *
     * <p>Filtering by key rather than reading whatever arrives is what lets these tests share one
     * broker: the key is a withdrawal id, and every test makes its own. Two tests running against
     * the same topic cannot see each other's messages.
     *
     * <p>The window is drained in full rather than returning at the first match, because most of the
     * assertions here are about a count. "Exactly one {@code WithdrawalApproved}" is the M4
     * criterion, and a helper that stopped at the first message could not tell it apart from three.
     *
     * @param topic which topic
     * @param key the message key to keep, normally a withdrawal id
     * @param window how long to listen
     * @return the matching messages, in the order they arrived
     */
    protected static List<ConsumerRecord<String, String>> drain(String topic, UUID key, Duration window) {
        return drain(topic, window).stream().filter(record -> key.toString().equals(record.key())).toList();
    }

    /**
     * Collects every message on a topic for a fixed window, whatever its key.
     *
     * @param topic which topic
     * @param window how long to listen
     * @return the messages, in the order they arrived
     */
    protected static List<ConsumerRecord<String, String>> drain(String topic, Duration window) {
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        // A group id nobody else uses, plus auto-offset-reset=earliest, so this consumer reads the
        // topic from the beginning however late it joins — the message it is looking for was very
        // likely published before it existed.
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "test-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class,
                        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));

            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : polled) {
                    received.add(record);
                }
            }
            return received;
        }
    }

    /**
     * The address of the broker, for a test that needs to talk to it directly.
     *
     * @return {@code host:port}
     */
    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
