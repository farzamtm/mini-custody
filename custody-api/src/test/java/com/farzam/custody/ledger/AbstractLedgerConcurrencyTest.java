package com.farzam.custody.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.support.AbstractPostgresTest;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The M1 test that matters: fifty threads racing for ten slots.
 *
 * <p>The bug this exists to catch is the lost update. An account holds 1 ETH; two requests read
 * that balance at the same instant, both conclude 0.4 is affordable, and both write their result.
 * The client has now withdrawn 0.8 ETH against a balance that was only ever checked against 1. Run
 * it with fifty threads instead of two and the arithmetic stops being subtle: exactly ten holds of
 * 0.1 ETH fit in 1 ETH, so eleven successes is a real loss of real money.
 *
 * <p>The test is shared by both subclasses so that pessimistic and optimistic locking are held to
 * an identical standard — see {@link PessimisticLedgerConcurrencyTest} and
 * {@link OptimisticLedgerConcurrencyTest}. Comparing two approaches where only one is tested is not
 * comparing them.
 *
 * <p>Note what is <em>not</em> asserted: which threads win. That is the database's business. What is
 * asserted is that the count is exactly right, the balance lands on zero, and it never went below.
 */
abstract class AbstractLedgerConcurrencyTest extends AbstractPostgresTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_ONE_ETH = new BigInteger("100000000000000000");
    private static final int THREADS = 50;
    private static final int EXPECTED_WINNERS = 10;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountRepository accounts;

    @Test
    void exactlyTenOfFiftyConcurrentHoldsSucceedAndTheBalanceLandsOnZero() throws Exception {
        Account external = accounts.save(Account.system(AccountType.EXTERNAL));
        Account client = accounts.save(Account.forClient(UUID.randomUUID()));
        Account pendingOut = accounts.save(Account.system(AccountType.PENDING_OUT));

        ledger.post(
                JournalKind.DEPOSIT,
                UUID.randomUUID(),
                List.of(new Entry(external.getId(), ONE_ETH.negate()), new Entry(client.getId(), ONE_ETH)));

        var startTogether = new CountDownLatch(1);
        var succeeded = new AtomicInteger();
        var refused = new AtomicInteger();
        List<Future<?>> submitted = new ArrayList<>();

        // A plain `int` counter would lose increments here for the same reason the database loses
        // updates without a lock — which is the whole point of the exercise.
        try (var pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < THREADS; i++) {
                submitted.add(pool.submit(() -> {
                    startTogether.await(); // pile up here, so the race is a race
                    try {
                        // A distinct reference id per thread: these are fifty different withdrawals
                        // competing for one balance, not one withdrawal delivered fifty times.
                        ledger.post(
                                JournalKind.WITHDRAWAL_HOLD,
                                UUID.randomUUID(),
                                List.of(
                                        new Entry(client.getId(), POINT_ONE_ETH.negate()),
                                        new Entry(pendingOut.getId(), POINT_ONE_ETH)));
                        succeeded.incrementAndGet();
                    } catch (InsufficientFundsException expected) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            startTogether.countDown();
        } // close() blocks until every task has finished

        // Surfaces anything that was neither a success nor an expected refusal — an optimistic
        // retry budget blown, say — instead of letting it hide inside a Future.
        for (Future<?> task : submitted) {
            task.get();
        }

        assertThat(succeeded.get()).as("holds that went through").isEqualTo(EXPECTED_WINNERS);
        assertThat(refused.get()).as("holds refused for want of funds").isEqualTo(THREADS - EXPECTED_WINNERS);

        // Never negative: 1 ETH went out in ten pieces of 0.1, and the money is all still in the
        // system, just parked in PENDING_OUT.
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(BigInteger.ZERO);
        assertThat(ledger.balanceOf(pendingOut.getId())).isEqualTo(ONE_ETH);
        assertThat(ledger.balanceOf(external.getId())).isEqualTo(ONE_ETH.negate());

        // And the cached balances still agree with the journal they are a cache of.
        assertThat(ledger.recomputedBalanceOf(client.getId())).isEqualTo(BigInteger.ZERO);
        assertThat(ledger.recomputedBalanceOf(pendingOut.getId())).isEqualTo(ONE_ETH);
    }
}
