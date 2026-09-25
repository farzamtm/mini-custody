package com.farzam.custody.messaging;

import com.farzam.events.EventFormatException;
import com.farzam.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The topics this service uses, and what happens to a message it cannot handle.
 *
 * <p>Serialisation is not configured here: keys and values are plain strings, set in
 * {@code application.yml}. The JSON is produced and parsed by {@code EventJson} in the application
 * code instead of by a Kafka serialiser, which sounds like extra work and is not. The outbox already
 * holds the payload as JSON text, so a JSON serialiser would parse it only to re-encode it; a
 * mismatch between the serialiser's Jackson settings and {@code EventJson}'s would silently change
 * the bytes that M3's approvers signed; and a deserialiser that fails inside Kafka's machinery
 * produces an error two layers below the listener, where this service's own exception types cannot
 * be used to decide whether a retry is worth attempting.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    /**
     * Three partitions, one replica.
     *
     * <p>The partition count is the ceiling on consumer parallelism — a partition is consumed by
     * exactly one member of a group, so a fourth signer instance would sit idle — and it cannot be
     * lowered later without recreating the topic. Three is room to grow for a system whose ordering
     * guarantee is per withdrawal, not global.
     *
     * <p>One replica because the local stack is a single broker. A real deployment runs three or
     * more with {@code min.insync.replicas=2}, which is what makes the producer's {@code acks=all}
     * mean "two independent machines have it" rather than "the one machine has it".
     */
    private static final int PARTITIONS = 3;

    private static final short REPLICAS = 1;

    /**
     * Declaring topics as beans rather than letting the broker auto-create them.
     *
     * <p>Auto-creation is on in most development brokers and produces a topic with the broker's
     * defaults — often one partition — the first time anybody mentions the name. That is how a
     * cluster ends up with a single-partition topic that quietly caps consumer parallelism at one,
     * and with {@code custody.withdrawal.v1} sitting next to {@code custody.withdrawals.v1} because
     * somebody typed it once. Declared here, the shape is reviewable and the name comes from a
     * shared constant.
     *
     * @return the topic custody-api publishes approvals to
     */
    @Bean
    NewTopic withdrawalsTopic() {
        return TopicBuilder.name(Topics.WITHDRAWALS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /** @return the topic the signer reports results on */
    @Bean
    NewTopic signerResultsTopic() {
        return TopicBuilder.name(Topics.SIGNER_RESULTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * The dead-letter topic, with the same partition count as the topic it shadows.
     *
     * <p>Not optional. The recoverer sends a failed message to the same partition number it arrived
     * on, so a dead-letter topic with fewer partitions makes recovery itself fail — and a failing
     * recoverer leaves the poison message exactly where it was, blocking its partition, which is the
     * situation the dead-letter topic exists to prevent.
     *
     * @return where messages go after every retry has failed
     */
    @Bean
    NewTopic signerResultsDeadLetterTopic() {
        return TopicBuilder.name(Topics.dlt(Topics.SIGNER_RESULTS)).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * Retry a few times, then set the message aside.
     *
     * <p>The alternative is the default behaviour of a naive consumer: fail, have the offset not
     * committed, be handed the same message again, fail again, forever. A message that can never
     * succeed — malformed JSON, a withdrawal id that does not exist — blocks its partition, and
     * every other withdrawal that happens to hash to that partition stops moving behind it. That is
     * a poison message, and the dead-letter topic is where it goes so the queue can carry on.
     *
     * <p><b>Retries are exponential and few.</b> Three of them, at 0.5 s, 1 s and 2 s, which covers
     * what transient failures actually look like here — a lock held a moment too long, a connection
     * pool briefly empty — and stops well short of a consumer that spends a minute on a message
     * nobody is going to be helped by.
     *
     * <p><b>A malformed event is not retried at all.</b> {@link EventFormatException} means the
     * bytes are not an event this service can read, and they will be exactly as unreadable in two
     * seconds. Registering it as non-retryable sends it straight to the dead-letter topic instead of
     * spending three attempts proving that JSON does not fix itself.
     *
     * <p><b>Neither is an unauthentic one.</b> {@link UnauthenticEventException} means the message
     * did not come from the signer, and a signature that does not verify now will not verify later.
     * Retrying it would be worse than pointless: the one way to produce these messages in volume is
     * deliberately, and three attempts each would let whoever is producing them cost this service
     * four times the work and block the partition for legitimate results behind them.
     *
     * @param template used to publish the failed message to {@code <topic>.DLT}
     * @return the container-wide error handler Spring Boot wires into every listener
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // The destination is resolved from Topics.dlt rather than left to the framework's default
        // suffix, which is not a stable thing to depend on: Spring Kafka 4 changed it from ".DLT" to
        // "-dlt". Depending on it failed in the quietest possible way — the recoverer logged a
        // successful publication, to a topic auto-created under the new name, while the declared
        // ".DLT" topic stayed empty and nobody was watching the one that had the messages.
        //
        // The partition number is carried over, which is why the topic declared above has to have as
        // many partitions as the one it shadows.
        var recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));

        // 0.5 s, then 1 s, then 2 s. The multiplier is an int literal widened to a double:
        // floating-point literals are banned repository-wide for the sake of the money paths, and an
        // exception for a backoff multiplier is not worth the precedent.
        var backOff = new ExponentialBackOff(500L, 2);
        // Three retries after the first failure, so four deliveries in all, spread over 3.5 s.
        backOff.setMaxAttempts(3);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(EventFormatException.class, UnauthenticEventException.class);
        return handler;
    }
}
