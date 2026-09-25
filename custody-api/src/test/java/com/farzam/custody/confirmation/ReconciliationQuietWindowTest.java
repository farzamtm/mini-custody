package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractChainTest;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The other side of {@code chain.stuck-after}: a withdrawal that has only just moved is not a
 * finding.
 *
 * <p>Its own class, and its own Spring context, because the threshold cannot be tested from both
 * directions with one value. {@link ReconciliationIntegrationTest} sets the budget to zero so that
 * every stall is reported immediately; here it is ten minutes so that nothing is.
 *
 * <p><b>Worth a context of its own because the failure it guards against is the quiet one.</b> A
 * reconciler that reported every approved and every broadcast withdrawal would still pass every
 * assertion in the other file — all of those check that a finding is present. It would simply
 * produce a report full of withdrawals that are working correctly, and a report that cries wolf is
 * one nobody reads, which costs more than having no report. That argument is made twice in
 * {@link Discrepancy} and in {@link Reconciler}; this is where it is enforced.
 */
@SpringBootTest
@WithoutKafka
@TestPropertySource(properties = "chain.stuck-after=10m")
class ReconciliationQuietWindowTest extends AbstractChainTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private Reconciler reconciler;

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

    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    /**
     * Approved a moment ago is the normal state of a withdrawal on its way to the signer, which
     * usually takes about a second. Reporting it would make the report useless.
     */
    @Test
    void aWithdrawalApprovedAMomentAgoIsNotAFinding() {
        Withdrawal withdrawal = approved();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.approvedChecked()).as("it was looked at, and passed").isPositive();
        assertThat(findingsFor(report, withdrawal)).isEmpty();
    }

    /** The same for a transaction that was broadcast a moment ago and is sitting in the mempool. */
    @Test
    void aWithdrawalBroadcastAMomentAgoIsNotAFinding() {
        Withdrawal withdrawal = broadcast();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.inFlightChecked()).isPositive();
        assertThat(findingsFor(report, withdrawal)).isEmpty();
    }

    // ---- Fixtures ------------------------------------------------------------

    private List<Discrepancy> findingsFor(ReconciliationReport report, Withdrawal withdrawal) {
        // Filtered by withdrawal: the database is shared with every other test in the suite, and a
        // stranded leftover of theirs is not this test's business.
        return report.discrepancies().stream().filter(d -> d.withdrawalId().equals(withdrawal.getId())).toList();
    }

    private Withdrawal approved() {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        approvals.approve(requested.getId(), List.of());
        return withdrawalRepository.findById(requested.getId()).orElseThrow();
    }

    private Withdrawal broadcast() {
        Withdrawal requested = approved();
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs("0x" + "deadbeef".repeat(8));
            return withdrawal;
        });
    }
}
