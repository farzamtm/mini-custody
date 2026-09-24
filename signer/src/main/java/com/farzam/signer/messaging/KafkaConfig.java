package com.farzam.signer.messaging;

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
 * The topics this service touches, and what happens to a message it cannot handle.
 *
 * <p>Both services declare the topics they use, and the overlap is deliberate rather than
 * duplicated by accident: topic creation is idempotent, and a service that declares what it depends
 * on starts correctly against an empty cluster no matter which one is deployed first. The names come
 * from {@link Topics}, so the two cannot disagree about spelling — a typo there does not fail, it
 * silently creates a second topic nobody reads.
 *
 * <p>Serialisation is not configured here either. Keys and values are strings, and the JSON is
 * produced and parsed by {@code EventJson}, for the reason custody-api's equivalent gives: a
 * deserialiser failing inside Kafka's machinery produces an error below the listener, where this
 * service's own exception types cannot be used to decide whether retrying is worth anything.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfig {

    private static final int PARTITIONS = 3;

    private static final short REPLICAS = 1;

    /**
     * The topic this service consumes.
     *
     * @return the approvals topic
     */
    @Bean
    NewTopic withdrawalsTopic() {
        return TopicBuilder.name(Topics.WITHDRAWALS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * The topic this service publishes results on.
     *
     * @return the results topic
     */
    @Bean
    NewTopic signerResultsTopic() {
        return TopicBuilder.name(Topics.SIGNER_RESULTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * Where an approval this service cannot handle ends up.
     *
     * <p>Same partition count as the topic it shadows, because the recoverer sends a failed message
     * to the partition number it arrived on. A dead-letter topic with fewer partitions makes
     * recovery itself fail, which leaves the poison message exactly where it was — blocking its
     * partition, which is the situation the dead-letter topic exists to prevent.
     *
     * @return the approvals dead-letter topic
     */
    @Bean
    NewTopic withdrawalsDeadLetterTopic() {
        return TopicBuilder.name(Topics.dlt(Topics.WITHDRAWALS)).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * Retry a few times, then set the message aside.
     *
     * <p>Worth being clear about what does and does not reach this handler, because the signer's
     * most important failure mode deliberately never does. A policy refusal — a signature that does
     * not verify, an approver this service does not trust, an amount over the cap — is not thrown.
     * It is committed as a {@code WithdrawalSigningFailed} event, so custody-api can release the
     * client's hold. Dead-lettering a refusal would leave the withdrawal APPROVED and the money held
     * indefinitely, with the explanation sitting in a topic nobody is watching.
     *
     * <p>What does reach it is infrastructure: the node unreachable, the database unavailable, a key
     * that will not decrypt. Three retries at 0.5 s, 1 s and 2 s, then the dead-letter topic.
     *
     * <p>A malformed event skips the retries entirely. {@link EventFormatException} means the bytes
     * are not an event this service can read, and they will be exactly as unreadable in two seconds.
     *
     * @param template used to publish the failed message
     * @return the container-wide error handler
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // Resolved through Topics.dlt rather than left to the framework's default suffix, which
        // changed from ".DLT" to "-dlt" in Spring Kafka 4 and would have moved every dead-lettered
        // message into an auto-created topic nobody declared, while logging a success.
        var recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));

        // 0.5 s, then 1 s, then 2 s. The multiplier is an int widened to a double: floating-point
        // literals are banned repository-wide for the sake of the money paths, and a backoff
        // multiplier is not worth an exception to that.
        var backOff = new ExponentialBackOff(500L, 2);
        backOff.setMaxAttempts(3);

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(EventFormatException.class);
        return handler;
    }
}
