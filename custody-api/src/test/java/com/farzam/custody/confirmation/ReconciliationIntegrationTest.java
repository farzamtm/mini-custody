package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.ledger.LedgerService;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation: does the chain still back up what the ledger says about it?
 *
 * <p>The findings are arranged by breaking things the running system cannot break on its own — a
 * chain rolled back past a settled transaction, a journal row deleted in SQL. That is the point of
 * the job. The watcher cannot catch its own mistakes, because the thing that would reveal them is
 * the thing it already believes, and every scenario here is one where nothing would ever have
 * emitted an event.
 *
 * <p>{@code chain.stuck-after} is zero here, so a withdrawal counts as stuck the moment it is
 * approved or broadcast. The alternative is a test that sleeps for ten minutes. The other side of
 * that threshold — that a withdrawal which has only just moved is <em>not</em> a finding — needs a
 * non-zero budget and lives in {@link ReconciliationQuietWindowTest}.
 */
@SpringBootTest
@WithoutKafka
@TestPropertySource(properties = "chain.stuck-after=0s")
class ReconciliationIntegrationTest extends AbstractChainTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final int REQUIRED = 3;

    @Autowired
    private Reconciler reconciler;

    @Autowired
    private ConfirmationWatcher watcher;

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

    @Autowired
    private JdbcClient jdbc;

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
    void aSettledWithdrawalTheChainStillHasReconciles() {
        Withdrawal withdrawal = settled();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(findingsFor(report, withdrawal)).isEmpty();
        assertThat(report.confirmedChecked()).as("and it actually checked something").isPositive();
    }

    /**
     * The serious finding: the ledger has recorded an outflow the chain no longer supports.
     *
     * <p>Arranged by rolling the chain back past a settled transaction, which is a reorg deeper than
     * {@code chain.confirmations} — the case the confirmation threshold is a bet against. Nothing in
     * the running system would ever notice: the watcher has finished with this withdrawal and will
     * never look at it again, and no event is emitted when a block quietly stops existing.
     */
    @Test
    void aSettlementWhoseTransactionHasVanishedIsReported() {
        String snapshot = chain.snapshot();
        Withdrawal withdrawal = settled();

        chain.revertTo(snapshot);

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.agrees()).isFalse();
        assertThat(findingsFor(report, withdrawal)).extracting(Discrepancy::kind)
                .contains(Discrepancy.Kind.SETTLED_WITHOUT_A_RECEIPT);
    }

    /**
     * A confirmed withdrawal with no settlement behind it.
     *
     * <p>Arranged by deleting the journal transaction, because nothing in the application can produce
     * this: the status change and the posting are written together. Which is exactly why the check is
     * worth having — what it catches is somebody's afternoon in {@code psql}, and that is not a
     * failure mode any amount of care in the Java prevents.
     */
    @Test
    void aConfirmedWithdrawalWithNoSettlementPostingIsReported() {
        Withdrawal withdrawal = settled();

        jdbc.sql(
                "delete from journal_entries where transaction_id in "
                        + "(select id from journal_transactions where kind = 'WITHDRAWAL_SETTLE' and reference_id = :id)")
                .param("id", withdrawal.getId())
                .update();
        jdbc.sql("delete from journal_transactions where kind = 'WITHDRAWAL_SETTLE' and reference_id = :id")
                .param("id", withdrawal.getId())
                .update();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(findingsFor(report, withdrawal)).extracting(Discrepancy::kind)
                .contains(Discrepancy.Kind.SETTLED_WITHOUT_A_POSTING);
    }

    /**
     * A settled withdrawal whose transaction the chain says reverted.
     *
     * <p>Should be impossible: the watcher reads {@code status} before it settles, and
     * {@code ConfirmationWatcherIntegrationTest} says so. Which is exactly why this check is worth
     * having, and why it is arranged by writing the status directly — a check that only fires when
     * the code it is checking is already correct is not a check. Reaching it in production would mean
     * either the receipt changed after settlement or the status test is broken, and the second is the
     * one a reconciler written against the same assumptions would never catch.
     */
    @Test
    void aSettlementOfATransactionTheChainSaysRevertedIsReported() {
        String reverter = chain.deployRevertingContract(funder);
        Withdrawal withdrawal = broadcast(chain.sendToRevertingContract(funder, reverter, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        // Straight to CONFIRMED, bypassing the watcher, which would have refused.
        jdbc.sql("update withdrawals set status = 'CONFIRMED' where id = :id").param("id", withdrawal.getId()).update();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(findingsFor(report, withdrawal)).extracting(Discrepancy::kind)
                .contains(Discrepancy.Kind.SETTLED_A_REVERTED_TRANSACTION);
    }

    /**
     * Broadcast, and the chain has never seen it.
     *
     * <p>Not damage — the signer resends on its own timer — but the signer never <em>reprices</em>,
     * so a transaction whose fee is too low stays unmined however many times it is sent. This is the
     * finding that says a person should look.
     */
    @Test
    void aWithdrawalBroadcastAndNeverMinedIsReported() {
        Withdrawal withdrawal = broadcast("0x" + "deadbeef".repeat(8));

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.inFlightChecked()).isPositive();
        assertThat(findingsFor(report, withdrawal)).extracting(Discrepancy::kind)
                .containsExactly(Discrepancy.Kind.BROADCAST_BUT_NOT_MINED);
    }

    /**
     * Approved, and it never left.
     *
     * <p>The gap this check closes. Both ways of reaching it are ordinary operational failures with
     * no retry of last resort: the outbox relay halts its batch on a failed send, and the signer's
     * listener makes live JSON-RPC calls inside its transaction, gets three attempts over about a
     * second and a half, and dead-letters to a topic nothing consumes. So an Ethereum node that is
     * briefly unreachable at the wrong moment strands the withdrawal for good.
     *
     * <p>Neither of those emits anything, which is why reconciliation has to be the thing that
     * notices. Before this, the report said everything agreed — and it was telling the truth, which
     * is the worst version of being wrong.
     */
    @Test
    void aWithdrawalApprovedAndNeverSignedIsReported() {
        Withdrawal withdrawal = approved();

        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.approvedChecked()).as("and it actually looked").isPositive();
        assertThat(report.agrees()).isFalse();
        assertThat(findingsFor(report, withdrawal)).extracting(Discrepancy::kind)
                .containsExactly(Discrepancy.Kind.APPROVED_BUT_NEVER_SIGNED);
    }

    /**
     * The money is still held while it is stranded, which is what makes it worth reporting.
     *
     * <p>Asserted separately from the finding because the two could come apart: a report that named
     * the withdrawal while the funds had quietly gone back would be describing a different and worse
     * problem.
     */
    @Test
    void aStrandedWithdrawalStillHasTheClientsFundsHeld() {
        Withdrawal withdrawal = approved();

        reconciler.reconcile();

        assertThat(withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus())
                .isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(ledger.balanceOf(accountId)).as("the hold is still against the client's balance")
                .isEqualTo(TEN_ETH.subtract(POINT_FOUR_ETH));
    }

    /**
     * A transaction that is mined but not yet deep enough is not a finding.
     *
     * <p>It is the system working slowly, which is what it is supposed to do. A reconciliation job
     * that reported every in-flight withdrawal would be a job whose output nobody reads.
     */
    @Test
    void aWithdrawalMinedButNotYetConfirmedIsNotAFinding() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(1);

        assertThat(findingsFor(reconciler.reconcile(), withdrawal)).isEmpty();
    }

    /** Reading the chain never writes to the ledger, however much it disagrees. */
    @Test
    void reconcilingChangesNothing() {
        String snapshot = chain.snapshot();
        Withdrawal withdrawal = settled();
        chain.revertTo(snapshot);

        reconciler.reconcile();
        reconciler.reconcile();

        assertThat(withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus())
                .as("a reporting job does not repair, because the right repair depends on why")
                .isEqualTo(WithdrawalStatus.CONFIRMED);
    }

    // ---- Fixtures ------------------------------------------------------------

    private List<Discrepancy> findingsFor(ReconciliationReport report, Withdrawal withdrawal) {
        // Filtered by withdrawal, because the database is shared with every other test in the suite
        // and their leftovers are not this test's business.
        return report.discrepancies().stream().filter(d -> d.withdrawalId().equals(withdrawal.getId())).toList();
    }

    private Withdrawal settled() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        watcher.checkBatch();
        return withdrawal;
    }

    private Withdrawal broadcast(String txHash) {
        Withdrawal requested = approved();
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs(txHash);
            return withdrawal;
        });
    }

    /**
     * A withdrawal that has been approved and is waiting for the signer.
     *
     * <p>Where every withdrawal in this file passes through, and where the stranded ones stop.
     */
    private Withdrawal approved() {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        // No approvals: nothing in this file is about who signed off. What an approved withdrawal
        // with genuine signatures on it looks like is ApprovalEventFlowIntegrationTest.
        approvals.approve(requested.getId(), List.of());
        return withdrawalRepository.findById(requested.getId()).orElseThrow();
    }
}
