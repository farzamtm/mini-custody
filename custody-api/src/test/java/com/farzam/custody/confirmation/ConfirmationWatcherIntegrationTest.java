package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.JournalTransactionRepository;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M6 acceptance test: a real transaction on a real chain, settled when it is deep enough.
 *
 * <p>Every receipt here is one Anvil produced. Nothing is stubbed, and the reason is in
 * {@link com.farzam.custody.support.AnvilContainer}: each field the watcher reads is a place where a
 * canned response would confirm the test author's assumption rather than check it.
 *
 * <p>The node runs with {@code --no-mining}, so blocks appear only when a test asks. "Settles at
 * exactly three confirmations and not at two" is then an assertion rather than a race.
 *
 * <p>Assertions about {@code PENDING_OUT}, {@code EXTERNAL} and {@code BANK_OPERATING} are written as
 * deltas: they are singletons shared with every other test in the suite.
 */
@SpringBootTest
@WithoutKafka
class ConfirmationWatcherIntegrationTest extends AbstractChainTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** Matches {@code chain.confirmations} in {@code application.yml}. */
    private static final int REQUIRED = 3;

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
    private JournalTransactionRepository journal;

    @Autowired
    private TransactionTemplate transactions;

    private TestChain chain;
    private String funder;
    private UUID accountId;

    @BeforeEach
    void fundAClientAndFindAnUnlockedAccount() {
        chain = chain();
        funder = chain.accounts().getFirst();
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    // ---- Waiting -------------------------------------------------------------

    @Test
    void aTransactionStillInTheMempoolSettlesNothing() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));

        assertThat(watcher.checkBatch()).isZero();

        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(receipts.find(withdrawal.getId())).as("there is nothing to record yet").isEmpty();
    }

    /**
     * Two of three. The receipt is written down, and nothing is settled.
     *
     * <p>The off-by-one is worth an assertion of its own: a transaction in the head block has one
     * confirmation, not zero, and a watcher that counted the other way would settle a block early —
     * on the block most likely to be reorganised away.
     */
    @Test
    void tooFewConfirmationsRecordTheReceiptAndWait() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED - 1);

        assertThat(watcher.checkBatch()).isZero();

        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(receipts.find(withdrawal.getId())).hasValueSatisfying(receipt -> {
            assertThat(receipt.succeeded()).isTrue();
            assertThat(receipt.confirmations()).isEqualTo(REQUIRED - 1);
        });
        assertThat(journal.countByKindAndReferenceId(JournalKind.WITHDRAWAL_SETTLE, withdrawal.getId())).isZero();
    }

    // ---- Settling ------------------------------------------------------------

    @Test
    void theThirdConfirmationSettlesTheHoldAgainstExternal() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        BigInteger heldBefore = ledger.balanceOf(SystemAccounts.PENDING_OUT);
        BigInteger externalBefore = ledger.balanceOf(SystemAccounts.EXTERNAL);
        chain.mine(REQUIRED);

        assertThat(watcher.checkBatch()).isEqualTo(1);

        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.CONFIRMED);
        assertThat(ledger.balanceOf(SystemAccounts.PENDING_OUT))
                .as("the hold is released, because the money really did leave")
                .isEqualTo(heldBefore.subtract(POINT_FOUR_ETH));
        // EXTERNAL also takes the fee, so this is a lower bound rather than an equality. The fee's
        // own assertion is the test below.
        assertThat(ledger.balanceOf(SystemAccounts.EXTERNAL))
                .isGreaterThanOrEqualTo(externalBefore.add(POINT_FOUR_ETH));
        assertThat(ledger.balanceOf(accountId)).as("the client is not credited anything back")
                .isEqualTo(TEN_ETH.subtract(POINT_FOUR_ETH));
    }

    /**
     * Gas is the custodian's cost, not the client's.
     *
     * <p>A fee taken out of the client's balance would be a withdrawal of 0.4 ETH that cost the
     * client more than 0.4 ETH, which is not what they were told. It comes out of
     * {@code BANK_OPERATING}, which the schema seeds with a float for exactly this.
     */
    @Test
    void theNetworkFeeIsBookedAgainstTheBankAndNotTheClient() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        BigInteger bankBefore = ledger.balanceOf(SystemAccounts.BANK_OPERATING);
        chain.mine(REQUIRED);

        watcher.checkBatch();

        assertThat(journal.countByKindAndReferenceId(JournalKind.NETWORK_FEE, withdrawal.getId())).isEqualTo(1);
        BigInteger fee = bankBefore.subtract(ledger.balanceOf(SystemAccounts.BANK_OPERATING));
        assertThat(fee).as("the bank paid the gas").isPositive();
        assertThat(fee).as("and it is the fee from the receipt")
                .isEqualTo(receipts.find(withdrawal.getId()).orElseThrow().feeWei());
        assertThat(ledger.balanceOf(accountId)).isEqualTo(TEN_ETH.subtract(POINT_FOUR_ETH));
    }

    /** The cached balance and the journal it is a cache of still agree after a settlement. */
    @Test
    void theRecomputedBalancesStillAgreeAfterSettling() {
        broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);

        watcher.checkBatch();

        assertThat(ledger.recomputedBalanceOf(SystemAccounts.PENDING_OUT))
                .isEqualTo(ledger.balanceOf(SystemAccounts.PENDING_OUT));
        assertThat(ledger.recomputedBalanceOf(SystemAccounts.BANK_OPERATING))
                .isEqualTo(ledger.balanceOf(SystemAccounts.BANK_OPERATING));
        assertThat(ledger.recomputedBalanceOf(SystemAccounts.EXTERNAL))
                .isEqualTo(ledger.balanceOf(SystemAccounts.EXTERNAL));
    }

    /**
     * Once settled, further passes do nothing — and it is the money the assertion is about.
     *
     * <p>A confirmed withdrawal is no longer in the watcher's query, which is the first guard. The
     * ledger's {@code unique (kind, reference_id)} is the second, and it is the one that matters:
     * settling twice would credit {@code EXTERNAL} for 0.8 ETH against a 0.4 ETH transaction, and
     * the ledger would still balance perfectly, because two well-formed postings were made instead
     * of one.
     */
    @Test
    void aSettledWithdrawalIsNotSettledAgain() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        watcher.checkBatch();
        BigInteger externalAfterSettling = ledger.balanceOf(SystemAccounts.EXTERNAL);

        chain.mine(REQUIRED);
        assertThat(watcher.checkBatch()).isZero();
        assertThat(watcher.checkBatch()).isZero();

        assertThat(journal.countByKindAndReferenceId(JournalKind.WITHDRAWAL_SETTLE, withdrawal.getId())).isEqualTo(1);
        assertThat(ledger.balanceOf(SystemAccounts.EXTERNAL)).isEqualTo(externalAfterSettling);
    }

    // ---- Going wrong ---------------------------------------------------------

    /**
     * Mined, and it reverted.
     *
     * <p>A receipt exists for a transaction that failed just as much as for one that worked, and the
     * fee is charged either way. Settling on the existence of a receipt would credit
     * {@code EXTERNAL} for money that never left.
     */
    @Test
    void aRevertedTransactionFailsTheWithdrawalAndGivesTheMoneyBack() {
        String reverter = chain.deployRevertingContract(funder);
        Withdrawal withdrawal = broadcast(chain.sendToRevertingContract(funder, reverter, POINT_FOUR_ETH));
        BigInteger bankBefore = ledger.balanceOf(SystemAccounts.BANK_OPERATING);
        chain.mine(REQUIRED);

        assertThat(watcher.checkBatch()).isEqualTo(1);

        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(reload(withdrawal).getFailureReason()).isEqualTo("the transaction reverted on chain");
        assertThat(ledger.balanceOf(accountId)).as("the hold came back").isEqualTo(TEN_ETH);
        assertThat(journal.countByKindAndReferenceId(JournalKind.WITHDRAWAL_SETTLE, withdrawal.getId()))
                .as("nothing was settled, because nothing left")
                .isZero();
        assertThat(ledger.balanceOf(SystemAccounts.BANK_OPERATING))
                .as("the chain charged for the failure and the ledger says so")
                .isLessThan(bankBefore);
    }

    /**
     * A receipt that was there last tick and is not there now.
     *
     * <p>The case the stored receipt exists for. Without it, a transaction that had been mined and
     * was then reorganised out would be indistinguishable from one that had never been mined at all,
     * and nothing would know anything had happened. Nothing needs undoing — settlement waits for
     * confirmations precisely so that a shallow reorg is recoverable — but the watcher has to notice
     * and stop claiming a receipt it no longer has.
     */
    @Test
    void aReceiptTheChainNoLongerHasIsForgottenAndTheWithdrawalKeepsWaiting() {
        String snapshot = chain.snapshot();
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(1);
        watcher.checkBatch();
        assertThat(receipts.find(withdrawal.getId())).as("the receipt was seen").isPresent();

        chain.revertTo(snapshot);
        assertThat(chain.hasReceipt(withdrawal.getTxHash())).as("and the chain has now dropped it").isFalse();

        assertThat(watcher.checkBatch()).isZero();

        assertThat(receipts.find(withdrawal.getId())).as("so the watcher stops claiming it").isEmpty();
        assertThat(statusOf(withdrawal)).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(ledger.balanceOf(accountId)).as("and the funds stay held, as they should")
                .isEqualTo(TEN_ETH.subtract(POINT_FOUR_ETH));
    }

    // ---- Fixtures ------------------------------------------------------------

    /**
     * A withdrawal driven to {@code BROADCAST} around a transaction that really is on the chain.
     *
     * <p>Through the state machine rather than by writing the row, so the withdrawal is in a state it
     * could actually have reached: requested, which books the hold, approved, which is what M3 and M4
     * do, and then broadcast, which is what the signer's result listener does.
     */
    private Withdrawal broadcast(String txHash) {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        // No approvals: nothing in this file is about who signed off. What an approved withdrawal
        // with genuine signatures on it looks like is ApprovalEventFlowIntegrationTest.
        approvals.approve(requested.getId(), List.of());
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs(txHash);
            return withdrawal;
        });
    }

    private WithdrawalStatus statusOf(Withdrawal withdrawal) {
        return reload(withdrawal).getStatus();
    }

    private Withdrawal reload(Withdrawal withdrawal) {
        return withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
    }
}
