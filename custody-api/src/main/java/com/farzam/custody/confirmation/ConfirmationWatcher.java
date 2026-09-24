package com.farzam.custody.confirmation;

import com.farzam.custody.chain.ChainProperties;
import com.farzam.custody.chain.EthereumRpc;
import com.farzam.custody.chain.TransactionReceipt;
import com.farzam.custody.ledger.Entry;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the chain what happened to every broadcast withdrawal, and settles the ones that are done.
 *
 * <p>This is the last step of the flow and the only one whose input is the outside world rather than
 * another part of this system. Everything before it is a claim — the client asked, the approvers
 * agreed, the signer says it sent something. This is where the ledger finds out whether the money
 * actually left.
 *
 * <p><b>Broadcast is not settled, and the gap between them is the whole reason this exists.</b> A
 * transaction in the mempool can be dropped, replaced, or mined into a block that is later
 * reorganised away. So the funds stay in {@code PENDING_OUT} — where they have been since the
 * request — until a receipt has {@code chain.confirmations} blocks on top of it. Only then does
 * {@code PENDING_OUT → EXTERNAL} get booked, and only then is the withdrawal {@code CONFIRMED}.
 *
 * <p><b>A mined transaction is not a successful one.</b> A receipt exists for a transfer that
 * reverted just as much as for one that worked, and the fee is charged either way. A reverted
 * transaction fails the withdrawal and releases the hold, and the fee is still booked, because the
 * custodian still paid it.
 *
 * <p><b>Why a poller and not an event subscription.</b> The same argument ADR 0005 makes for the
 * outbox relay: {@code eth_subscribe} needs a WebSocket, a reconnection policy and a plan for the
 * blocks missed while it was disconnected — and that plan is a poller. Polling at two seconds
 * against a chain that produces a block every twelve is not the thing under load here.
 */
@Component
@SuppressFBWarnings(
        value = "CRLF_INJECTION_LOGS",
        justification = "Every value logged in this class is a UUID, a long, or a transaction hash "
                + "read back from this service's own database, where it was written from a signer "
                + "event. None of them can carry a newline, so none of them can forge a log line. "
                + "Declared on the type because the logging is spread across the private methods "
                + "the public one delegates to.")
public class ConfirmationWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ConfirmationWatcher.class);

    private final WithdrawalRepository withdrawals;
    private final EthereumRpc chain;
    private final ReceiptStore receipts;
    private final LedgerService ledger;
    private final ChainProperties properties;

    ConfirmationWatcher(WithdrawalRepository withdrawals, EthereumRpc chain, ReceiptStore receipts,
            LedgerService ledger, ChainProperties properties) {
        this.withdrawals = withdrawals;
        this.chain = chain;
        this.receipts = receipts;
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * One pass over the withdrawals that are on the chain but not yet finished.
     *
     * <p><b>The rows are claimed with {@code FOR UPDATE SKIP LOCKED}</b>, exactly as the outbox
     * relay claims its batch and for the same reason: a second instance of this service is a normal
     * thing to have, and plain {@code FOR UPDATE} would make it block on the first instance's rows
     * and then settle them again. {@code SKIP LOCKED} gives each watcher a disjoint batch. The
     * ledger's {@code unique (kind, reference_id)} is still underneath it, so even two watchers that
     * somehow claimed the same withdrawal could not book the settlement twice.
     *
     * <p><b>The RPC calls happen while the rows are locked</b>, which is the trade ADR 0005 already
     * made for the relay's Kafka sends. Reading a receipt outside the transaction and settling in a
     * second one would shorten the lock and open a window in which the status changed underneath the
     * answer. The bound on the lock is {@code chain.rpc-timeout}, and the calls are reads against a
     * node that is normally in the same data centre.
     *
     * <p><b>An unreachable node stops the batch rather than settling anything.</b> {@code
     * RpcException} propagates, the transaction rolls back, the locks go, and the next tick tries
     * again. That is the important direction: a node that cannot be asked has said nothing, and "no
     * answer" must never be read as "no receipt".
     *
     * @return how many withdrawals this pass finished, for a test that wants to know something
     *     happened without sleeping
     */
    @Transactional
    public int checkBatch() {
        List<Withdrawal> broadcast = withdrawals.findBroadcastForUpdateSkipLocked(Limit.of(properties.batchSize()));
        if (broadcast.isEmpty()) {
            return 0;
        }

        // Once per batch, not once per withdrawal. Two transactions mined in the same block must be
        // counted against the same head, or one could reach its third confirmation a tick before the
        // other for a reason nothing in the data would explain.
        long head = chain.blockNumber();
        int finished = 0;

        for (Withdrawal withdrawal : broadcast) {
            if (settleIfReady(withdrawal, head)) {
                finished++;
            }
        }
        return finished;
    }

    /**
     * @return true if this withdrawal reached a terminal state in this pass
     */
    private boolean settleIfReady(Withdrawal withdrawal, long head) {
        Optional<TransactionReceipt> found = chain.receiptOf(withdrawal.getTxHash());

        if (found.isEmpty()) {
            forgetAnyReceiptTheChainHasDropped(withdrawal);
            return false;
        }

        TransactionReceipt receipt = found.get();
        long confirmations = receipt.confirmationsAt(head);
        receipts.observe(withdrawal.getId(), withdrawal.getTxHash(), receipt, (int) confirmations);

        if (confirmations < properties.confirmations()) {
            LOG.debug(
                    "withdrawal {} has {} of {} confirmations",
                    withdrawal.getId(),
                    confirmations,
                    properties.confirmations());
            return false;
        }

        if (receipt.successful()) {
            settle(withdrawal, receipt, confirmations);
        } else {
            releaseAfterRevert(withdrawal, receipt);
        }
        return true;
    }

    /**
     * A receipt that was there last tick and is not there now.
     *
     * <p>This is a reorg, and it is the case a watcher with no memory could not see: without the
     * stored row, a transaction that had been mined and was then reorganised out would be
     * indistinguishable from one that had never been mined at all. Nothing needs undoing — the
     * withdrawal never left {@code BROADCAST}, because settlement waits for confirmations precisely
     * so that this is recoverable — but it is worth a warning, because the alternative explanation
     * for a receipt disappearing is that the signer's transaction was replaced by somebody else's at
     * the same nonce.
     */
    private void forgetAnyReceiptTheChainHasDropped(Withdrawal withdrawal) {
        if (receipts.find(withdrawal.getId()).isPresent()) {
            LOG.warn(
                    "the chain no longer has a receipt for withdrawal {} ({}); it was reorganised out "
                            + "or replaced, and settlement waits for the signer to resend",
                    withdrawal.getId(),
                    withdrawal.getTxHash());
            receipts.forget(withdrawal.getId());
        }
    }

    /**
     * The money really left.
     *
     * <p>{@code PENDING_OUT −amount / EXTERNAL +amount}: the hold, which has existed since the
     * request, becomes an actual outflow. This is the only place in the system that books
     * {@code WITHDRAWAL_SETTLE}, and the ledger's unique index means it books once however many
     * times a watcher is asked to.
     */
    private void settle(Withdrawal withdrawal, TransactionReceipt receipt, long confirmations) {
        withdrawal.moveTo(WithdrawalStatus.CONFIRMED);
        ledger.post(
                JournalKind.WITHDRAWAL_SETTLE,
                withdrawal.getId(),
                List.of(
                        new Entry(SystemAccounts.PENDING_OUT, withdrawal.getAmount().negate()),
                        new Entry(SystemAccounts.EXTERNAL, withdrawal.getAmount())));
        bookTheFee(withdrawal, receipt);
        LOG.info(
                "withdrawal {} confirmed at block {} with {} confirmations",
                withdrawal.getId(),
                receipt.blockNumber(),
                confirmations);
    }

    /**
     * Mined, and it reverted.
     *
     * <p>The client gets their money back and the custodian keeps the bill. A plain ETH transfer to
     * an externally-owned account has essentially no way to revert, so in this system reaching here
     * means something is wrong that nobody predicted — which is exactly why it is handled rather than
     * assumed away. What it must not do is settle: the value did not move, so booking
     * {@code PENDING_OUT → EXTERNAL} would record an outflow that never happened.
     */
    private void releaseAfterRevert(Withdrawal withdrawal, TransactionReceipt receipt) {
        withdrawal.endWith(WithdrawalStatus.FAILED, "the transaction reverted on chain");
        ledger.post(
                JournalKind.WITHDRAWAL_RELEASE,
                withdrawal.getId(),
                List.of(
                        new Entry(SystemAccounts.PENDING_OUT, withdrawal.getAmount().negate()),
                        new Entry(withdrawal.getAccountId(), withdrawal.getAmount())));
        bookTheFee(withdrawal, receipt);
        LOG.warn(
                "withdrawal {} reverted at block {}; the hold has gone back",
                withdrawal.getId(),
                receipt.blockNumber());
    }

    /**
     * Gas, which the custodian pays and the client does not.
     *
     * <p>Booked for a revert as well as for a success, because the chain charges for both — a fee
     * only recorded on the happy path is a ledger that drifts from the hot wallet by exactly the
     * amount of every failure, which is the kind of discrepancy that takes a week to find.
     *
     * <p>A separate journal transaction rather than two more entries on the settlement, so
     * "withdrawal 0.4 ETH left" and "it cost 0.0004 ETH to send" stay separately readable and
     * separately reversible. They commit together regardless: one {@code @Transactional} method.
     *
     * <p>A zero fee — which Anvil can produce with a zero base fee and no tip — is skipped rather
     * than booked, because a posting of zero on both sides is a journal transaction that says
     * nothing and still takes a row.
     */
    private void bookTheFee(Withdrawal withdrawal, TransactionReceipt receipt) {
        BigInteger fee = receipt.feeWei();
        if (fee.signum() <= 0) {
            return;
        }
        ledger.post(
                JournalKind.NETWORK_FEE,
                withdrawal.getId(),
                List.of(
                        new Entry(SystemAccounts.BANK_OPERATING, fee.negate()),
                        new Entry(SystemAccounts.EXTERNAL, fee)));
    }
}
