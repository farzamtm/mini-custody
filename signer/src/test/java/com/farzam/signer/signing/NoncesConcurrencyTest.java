package com.farzam.signer.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.farzam.signer.support.AbstractSignerTest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nonce reservation under contention, which is the only condition it is interesting under.
 *
 * <p>Two withdrawals arriving at the same moment is the whole reason the counter is in Postgres
 * behind a {@code FOR UPDATE} rather than in a field. Without the lock both would read the same
 * value, both would sign with it, and the network would mine one and silently drop the other —
 * leaving a withdrawal marked BROADCAST in custody-api that no amount of receipt polling will ever
 * confirm.
 */
@SpringBootTest
class NoncesConcurrencyTest extends AbstractSignerTest {

    private static final int THREADS = 20;

    @Autowired
    private Nonces nonces;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Twenty threads reserving against one address get twenty different nonces, and they are
     * 0 to 19 with no gaps.
     *
     * <p>"All different" alone would be satisfied by a counter that skipped, and a skipped nonce is
     * not a cosmetic problem: a gap blocks every later transaction from the wallet until something
     * fills it.
     */
    @Test
    void concurrentReservationsAgainstOneWalletNeverCollideAndNeverSkip() {
        // A wallet with no history on chain, so the seed is 0 and the expected set is exact. It also
        // exercises the seeding path, which only ever runs once per address.
        String address = freshAddress().toLowerCase(Locale.ROOT);
        var transactions = new TransactionTemplate(transactionManager);

        List<Long> reserved;
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Callable<Long>> tasks = IntStream.range(0, THREADS)
                    .<Callable<Long>>mapToObj(i -> () -> transactions.execute(status -> nonces.reserve(address)))
                    .toList();
            reserved = pool.invokeAll(tasks).stream().map(NoncesConcurrencyTest::get).toList();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }

        assertThat(Set.copyOf(reserved)).hasSize(THREADS);
        assertThat(reserved)
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, THREADS).mapToObj(Long::valueOf).toList());
    }

    /**
     * A nonce taken in its own transaction stays taken when the signing that needed it rolls back,
     * and the gap it leaves blocks the wallet. {@code MANDATORY} turns that mistake into an
     * exception the first time anyone makes it.
     */
    @Test
    void reservingOutsideATransactionIsRefused() {
        assertThatExceptionOfType(IllegalTransactionStateException.class)
                .isThrownBy(() -> nonces.reserve(HOT_WALLET_ADDRESS));
    }

    private static Long get(Future<Long> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException(failure.getCause());
        }
    }
}
