package com.farzam.custody.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind {@link OutboxRelay}.
 *
 * <p>A separate bean from the relay for the reason given on {@link OutboxRelay#publishBatch()}: a
 * {@code @Scheduled} method that called a {@code @Transactional} method on its own object would
 * bypass the proxy and run the whole batch with no transaction at all — locking nothing, and marking
 * rows published one autocommitted statement at a time.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}. Fixed delay measures the gap between the end of one
 * run and the start of the next, so a slow batch simply delays the following one. Fixed rate would
 * try to keep to the schedule and start overlapping runs against a slow broker, which is when
 * {@code SKIP LOCKED} would be doing real work to save the relay from itself.
 *
 * <p>The property exists so tests can switch the timer off and call {@link OutboxRelay#publishBatch}
 * themselves. A test that has to sleep to find out what happened is a test that will fail on a busy
 * CI runner for no reason.
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
     * <p>500 ms is a latency budget, not a throughput one: a batch takes as many rows as it finds,
     * so the interval decides how long an approved withdrawal waits before the signer hears about
     * it, and not how fast the backlog drains. The cost of a tick that finds nothing is one indexed
     * query against the partial index, which is why it can afford to be this frequent.
     */
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval:500ms}")
    void tick() {
        relay.publishBatch();
    }
}
