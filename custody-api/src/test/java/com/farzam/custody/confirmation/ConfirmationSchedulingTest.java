package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractChainTest;
import com.farzam.custody.support.TestChain;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The timer, which is the one part of the confirmation path no other test exercises.
 *
 * <p>Everywhere else drives {@link ConfirmationWatcher#checkBatch} directly, so that an assertion
 * about "three confirmations and not two" is not racing a background thread. That leaves a gap
 * exactly where the production wiring is: nothing proved that anything ever <em>calls</em>
 * {@code checkBatch}. A withdrawal would reach {@code BROADCAST} and stay there for ever, and every
 * other test in the module would still pass.
 *
 * <p>So this one turns the timer on and then does nothing at all. No {@code checkBatch}, no poll —
 * it mines the blocks and waits for the application to notice on its own.
 *
 * <p>{@code @TestPropertySource} is what makes that possible, and it only works because
 * {@link AbstractChainTest} deliberately does not set the same property through
 * {@code @DynamicPropertySource}, which would outrank it.
 */
@SpringBootTest
@WithoutKafka
@TestPropertySource(properties = {"chain.watcher.scheduled=true", "chain.poll-interval=200ms"})
class ConfirmationSchedulingTest extends AbstractChainTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** Generous next to a 200 ms tick; this is a deadline, not an expectation. */
    private static final Duration SETTLES_WITHIN = Duration.ofSeconds(20);

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalApprovalService approvals;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Autowired
    private TransactionTemplate transactions;

    private TestChain chain;
    private String funder;
    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        chain = chain();
        funder = chain.accounts().getFirst();
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    @Test
    void theTimerSettlesAConfirmedWithdrawalWithoutAnybodyAskingIt() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));

        chain.mine(3);

        await().atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.CONFIRMED));
    }

    /**
     * A transaction that is mined but not deep enough is left alone, tick after tick.
     *
     * <p>The other direction of the same wiring, and worth having because a timer that settled
     * everything it saw would pass the test above. {@code during} rather than {@code atMost}: the
     * assertion has to hold for the whole window, across a dozen or so ticks, rather than be true
     * once.
     */
    @Test
    void theTimerLeavesAWithdrawalAloneUntilItIsDeepEnough() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));

        chain.mine(2);

        await().during(Duration.ofSeconds(2))
                .atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.BROADCAST));

        // And then it does settle, so the wait above was the threshold holding rather than the
        // timer having quietly died.
        chain.mine(1);
        await().atMost(SETTLES_WITHIN)
                .untilAsserted(() -> assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.CONFIRMED));
    }

    // ---- Fixtures ------------------------------------------------------------

    private Withdrawal broadcast(String txHash) {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        approvals.approve(requested.getId(), List.of());
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs(txHash);
            return withdrawal;
        });
    }

    private WithdrawalStatus statusOf(Withdrawal withdrawal) {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus();
    }
}
