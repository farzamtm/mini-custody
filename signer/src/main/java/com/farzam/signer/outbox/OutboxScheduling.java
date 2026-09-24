package com.farzam.signer.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind {@link OutboxRelay}.
 *
 * <p>A separate bean from the relay because a {@code @Scheduled} method calling a
 * {@code @Transactional} method on its own object bypasses the proxy: the batch would run with no
 * transaction at all, locking nothing and marking rows published one autocommitted statement at a
 * time.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}. Fixed delay measures the gap between the end of one
 * run and the start of the next, so a slow batch simply delays the following one; fixed rate would
 * start overlapping runs against a slow broker.
 *
 * <p>The property exists so tests can switch the timer off and drive the relay themselves. A test
 * that sleeps to find out what happened is a test that fails on a loaded CI runner for no reason.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.scheduled", havingValue = "true", matchIfMissing = true)
class OutboxScheduling {

    private final OutboxRelay relay;

    OutboxScheduling(OutboxRelay relay) {
        this.relay = relay;
    }

    /**
     * Ticks the relay.
     *
     * <p>500 ms is a latency budget rather than a throughput one: a batch takes as many rows as it
     * finds, so the interval decides how long custody-api waits to hear that a withdrawal was
     * signed, not how fast a backlog drains.
     */
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval:500ms}")
    void tick() {
        relay.publishBatch();
    }
}
