package com.farzam.signer.signing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind {@link Broadcaster#resendBacklog()}.
 *
 * <p>A separate bean so a test can switch the timer off and call the method itself. A test that
 * sleeps to find out whether something was resent is a test that fails on a loaded CI runner and
 * passes on a retry, which is worse than no test because it teaches people to press the button
 * again.
 *
 * <p>Five seconds against a retry-after of ten: the job never competes with the listener's own
 * broadcast, which happens milliseconds after the commit, and a transaction that genuinely did not
 * get out waits at most fifteen seconds for its second chance.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "signer.broadcast.scheduled", havingValue = "true", matchIfMissing = true)
class BroadcastScheduling {

    private final Broadcaster broadcaster;

    BroadcastScheduling(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Ticks the retry job.
     *
     * <p>{@code fixedDelay}: a pass that takes a while — a node timing out on every row — delays the
     * next one instead of stacking another on top of it.
     */
    @Scheduled(fixedDelayString = "${signer.broadcast.retry-interval:5s}")
    void tick() {
        broadcaster.resendBacklog();
    }
}
