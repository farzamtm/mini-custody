package com.farzam.custody.confirmation;

import com.farzam.custody.chain.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The clock behind {@link ConfirmationWatcher}.
 *
 * <p>A separate bean from the watcher, for the reason {@code OutboxScheduling} gives: a
 * {@code @Scheduled} method calling a {@code @Transactional} method on its own object would bypass
 * the proxy and run the whole batch with no transaction at all — locking nothing, and settling
 * withdrawals one autocommitted statement at a time.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}. Fixed delay measures the gap between the end of one
 * run and the start of the next, so a batch that waits on a slow node simply delays the following
 * one. Fixed rate would start overlapping runs against exactly the node that was already struggling.
 *
 * <p>The property exists so tests can switch the timer off and call
 * {@link ConfirmationWatcher#checkBatch} themselves. A test that has to sleep to find out what
 * happened is a test that fails on a busy CI runner for no reason.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "chain.watcher.scheduled", havingValue = "true", matchIfMissing = true)
class ConfirmationScheduling {

    private static final Logger LOG = LoggerFactory.getLogger(ConfirmationScheduling.class);

    private final ConfirmationWatcher watcher;

    ConfirmationScheduling(ConfirmationWatcher watcher) {
        this.watcher = watcher;
    }

    /**
     * Ticks the watcher.
     *
     * <p><b>{@link RpcException} is caught, and nothing else is.</b> An unreachable node is an
     * operational condition rather than a fault: the next tick may well resolve it, and a scheduled
     * job that logs a stack trace at error level every two seconds while a node reboots is a job
     * whose output gets filtered out. Anything else — a database failure, a bug — goes to Spring's
     * own error handler, which logs it loudly and keeps the timer running; that is the right
     * treatment for a surprise and the wrong one for a flaky network.
     *
     * <p>Catching it here rather than inside the watcher is what keeps the important property
     * intact. The transaction has already rolled back by the time control arrives here, because the
     * exception passed through the {@code @Transactional} proxy on its way out — so nothing was
     * half-settled, and "the node did not answer" never reached the ledger as "there is no receipt".
     */
    @Scheduled(fixedDelayString = "${chain.poll-interval:2s}")
    void tick() {
        try {
            watcher.checkBatch();
        } catch (RpcException unreachable) {
            LOG.warn("a confirmation pass could not reach the chain; the next tick will try again", unreachable);
        }
    }
}
