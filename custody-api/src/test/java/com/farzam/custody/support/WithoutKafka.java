package com.farzam.custody.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * For a test that needs the application but not a broker.
 *
 * <p>Without this, a context with no Kafka behind it does three unhelpful things. {@code KafkaAdmin}
 * tries to create the declared topics during startup and blocks for its thirty-second operation
 * timeout before concluding that nobody is listening — once per context, for every context in the
 * suite. The listener container retries a connection in the background for the length of the run.
 * And the outbox relay ticks twice a second against the same absent broker.
 *
 * <p>The worse problem is that on a developer's laptop none of that fails. {@code docker compose up}
 * puts a real broker on exactly the default {@code localhost:9092}, so these tests would quietly
 * publish into it, consume whatever happened to be there already, and behave differently depending
 * on whether the developer had the stack running. A test whose result depends on something outside
 * the repository is not a test.
 *
 * <p>Tests that do want a broker extend {@link AbstractKafkaTest} instead, which starts one and
 * points the application at it. They deliberately do not carry this annotation, so they run against
 * the same Kafka configuration production does.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@TestPropertySource(
        properties = {"spring.kafka.admin.auto-create=false", "spring.kafka.listener.auto-startup=false",
                "outbox.relay.scheduled=false"})
public @interface WithoutKafka {
}
