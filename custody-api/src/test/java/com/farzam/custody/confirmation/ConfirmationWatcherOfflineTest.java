package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.farzam.custody.chain.RpcException;
import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.support.AbstractPostgresTest;
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
 * What the watcher does when the chain cannot be asked: nothing, for ever, loudly.
 *
 * <p>This is the safety property the whole settlement path rests on, and it is the one that would be
 * easiest to break by accident. A node that does not answer has said nothing — and "nothing" must
 * never be read as "there is no receipt", because that is a state the watcher acts on. Swallow the
 * exception one layer too low, or default a missing block number to zero, and the ledger starts
 * making decisions about money on the strength of a network timeout.
 *
 * <p>No Anvil, deliberately: {@code chain.rpc-url} points at a port with nothing behind it, which is
 * a more honest outage than a container that is merely slow. That also means this extends
 * {@link AbstractPostgresTest} rather than {@code AbstractChainTest} — the latter fixes the URL
 * through {@code @DynamicPropertySource}, which would outrank the override here.
 *
 * <p>The timer is on, because a watcher that has stopped ticking would also settle nothing and pass
 * a weaker version of this test.
 */
@SpringBootTest
@WithoutKafka
@TestPropertySource(
        properties = {"chain.watcher.scheduled=true", "chain.poll-interval=200ms", "chain.rpc-url=http://127.0.0.1:1",
                "chain.rpc-timeout=250ms"})
class ConfirmationWatcherOfflineTest extends AbstractPostgresTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** A hash no key could be mistaken for. See {@code .gitleaks.toml} for why that matters. */
    private static final String TX_HASH = "0x" + "deadbeef".repeat(8);

    @Autowired
    private ConfirmationWatcher watcher;

    @Autowired
    private ReceiptStore receipts;

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
    private LedgerService ledger;

    @Autowired
    private TransactionTemplate transactions;

    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    @Test
    void aPassAgainstAnUnreachableNodeThrowsRatherThanFindingNoReceipt() {
        broadcast();

        assertThatExceptionOfType(RpcException.class).isThrownBy(watcher::checkBatch);
    }

    /**
     * Ticks keep failing and the withdrawal keeps its hold.
     *
     * <p>{@code during} across a two-second window at a 200 ms interval is roughly ten failed passes.
     * What is being asserted is the absence of a state change — the status, the client's balance and
     * the receipt table all untouched — because the damage a mishandled outage would do is not a
     * crash, it is a settlement nobody can explain afterwards.
     */
    @Test
    void repeatedFailedTicksSettleNothingAndLeaveTheHoldWhereItIs() {
        Withdrawal withdrawal = broadcast();
        BigInteger heldBefore = ledger.balanceOf(SystemAccounts.PENDING_OUT);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.BROADCAST);
            assertThat(receipts.find(withdrawal.getId())).isEmpty();
        });

        assertThat(ledger.balanceOf(accountId)).isEqualTo(TEN_ETH.subtract(POINT_FOUR_ETH));
        assertThat(ledger.balanceOf(SystemAccounts.PENDING_OUT)).isEqualTo(heldBefore);
    }

    // ---- Fixtures ------------------------------------------------------------

    private Withdrawal broadcast() {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        approvals.approve(requested.getId(), List.of());
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs(TX_HASH);
            return withdrawal;
        });
    }

    private WithdrawalStatus statusOf(Withdrawal withdrawal) {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus();
    }
}
