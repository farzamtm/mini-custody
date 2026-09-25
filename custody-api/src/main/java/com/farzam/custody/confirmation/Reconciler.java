package com.farzam.custody.confirmation;

import com.farzam.custody.chain.ChainProperties;
import com.farzam.custody.chain.EthereumRpc;
import com.farzam.custody.chain.TransactionReceipt;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.JournalTransactionRepository;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks the chain whether the ledger's story about it is still true.
 *
 * <p>The confirmation watcher settles a withdrawal once, on the strength of what the chain said at
 * that moment. This goes back and checks, and the difference between the two is the point: a watcher
 * cannot catch its own mistakes, because the thing that would reveal them is the thing it already
 * believes. A reorg deeper than three blocks, a receipt that changed after settlement, a status
 * written by somebody's afternoon in {@code psql} — none of those produce an event anybody handles.
 * They produce a ledger that disagrees with the world, silently, until somebody asks.
 *
 * <p><b>Two of the checks are about the chain disagreeing, and two are about nothing happening.</b>
 * A settled withdrawal the chain will not vouch for is a contradiction. A withdrawal that has sat in
 * APPROVED or BROADCAST past {@code chain.stuck-after} is not — the ledger and the chain agree
 * perfectly that nothing has happened, which is the problem. Both kinds are worth the same report
 * because both end with a client's money held and nobody acting.
 *
 * <p><b>It reads and reports; it does not repair.</b> Nothing here writes to the ledger, and that is
 * deliberate rather than unfinished. Every finding below has more than one possible cause and the
 * right remedy depends on which — a settlement with no receipt behind it might want a reversing
 * entry, or might mean the node being asked is on the wrong chain — and a job that guessed would
 * turn a detectable problem into two. The ledger is append-only, so the repair is a reversing entry
 * made by somebody who has looked.
 *
 * <p><b>What it deliberately does not check.</b> Whether the hot wallet's on-chain balance matches
 * what the ledger says the custodian holds. It cannot, in this system: deposits are fabricated by
 * {@code POST /dev/deposits} rather than observed on chain, because nothing here watches for
 * incoming transfers. Reporting that as a discrepancy on every run would be crying wolf, so the
 * balance is reported as information and the gap is named in the README instead.
 */
@Service
public class Reconciler {

    private final WithdrawalRepository withdrawals;
    private final JournalTransactionRepository journal;
    private final EthereumRpc chain;
    private final ChainProperties properties;

    // No ReceiptStore. What this service last wrote down is not evidence about the chain, and asking
    // it would be comparing the service to itself — see checkSettlement.
    Reconciler(WithdrawalRepository withdrawals, JournalTransactionRepository journal, EthereumRpc chain,
            ChainProperties properties) {
        this.withdrawals = withdrawals;
        this.journal = journal;
        this.chain = chain;
        this.properties = properties;
    }

    /**
     * Compares every settled and in-flight withdrawal against the chain.
     *
     * <p>{@code readOnly}, which is not decoration: it tells Hibernate not to dirty-check, and it
     * tells the next person reading this that a reconciliation job cannot have been the thing that
     * changed a balance.
     *
     * @return what was checked, what disagreed, and what the hot wallet holds
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile() {
        List<Discrepancy> found = new ArrayList<>();
        Instant stuckBefore = Instant.now().minus(properties.stuckAfter());

        List<Withdrawal> confirmed = withdrawals.findByStatus(WithdrawalStatus.CONFIRMED);
        for (Withdrawal withdrawal : confirmed) {
            checkSettlement(withdrawal, found);
        }

        List<Withdrawal> inFlight = withdrawals.findByStatus(WithdrawalStatus.BROADCAST);
        for (Withdrawal withdrawal : inFlight) {
            checkStillMoving(withdrawal, stuckBefore, found);
        }

        List<Withdrawal> approved = withdrawals.findByStatus(WithdrawalStatus.APPROVED);
        for (Withdrawal withdrawal : approved) {
            checkStillBeingSigned(withdrawal, stuckBefore, found);
        }

        return new ReconciliationReport(
                Instant.now(),
                confirmed.size(),
                inFlight.size(),
                approved.size(),
                hotWalletBalance(),
                List.copyOf(found));
    }

    /**
     * A withdrawal the ledger says is done. Does the chain agree?
     *
     * <p>Three questions, and the first is asked of the chain rather than of
     * {@code transaction_receipts}. Checking a stored receipt against a stored settlement would be
     * comparing this service to itself, which proves only that it is consistent — and a service that
     * has settled something that never happened is perfectly consistent about it.
     */
    private void checkSettlement(Withdrawal withdrawal, List<Discrepancy> found) {
        if (journal.countByKindAndReferenceId(JournalKind.WITHDRAWAL_SETTLE, withdrawal.getId()) == 0) {
            found.add(
                    new Discrepancy(
                            withdrawal.getId(),
                            Discrepancy.Kind.SETTLED_WITHOUT_A_POSTING,
                            "the withdrawal is CONFIRMED but no WITHDRAWAL_SETTLE was booked for it"));
        }

        Optional<TransactionReceipt> onChain = chain.receiptOf(withdrawal.getTxHash());
        if (onChain.isEmpty()) {
            found.add(
                    new Discrepancy(
                            withdrawal.getId(),
                            Discrepancy.Kind.SETTLED_WITHOUT_A_RECEIPT,
                            "the ledger has settled this withdrawal, and the chain has no receipt for "
                                    + "its transaction"));
            return;
        }
        if (!onChain.get().successful()) {
            found.add(
                    new Discrepancy(
                            withdrawal.getId(),
                            Discrepancy.Kind.SETTLED_A_REVERTED_TRANSACTION,
                            "the ledger has settled this withdrawal, and the chain says the transaction "
                                    + "reverted"));
        }
    }

    /**
     * A withdrawal that is on the wire. Has it arrived, and if not, how long has it been?
     *
     * <p>Only the second half is a finding. A transaction that has been broadcast for ten seconds and
     * is not mined is the normal state of a transaction; one that has been broadcast for an hour is
     * either underpriced or lost, and nothing in this system reprices.
     */
    private void checkStillMoving(Withdrawal withdrawal, Instant stuckBefore, List<Discrepancy> found) {
        if (!withdrawal.getUpdatedAt().isBefore(stuckBefore)) {
            return;
        }
        if (chain.receiptOf(withdrawal.getTxHash()).isPresent()) {
            // Mined, and the watcher simply has not counted enough confirmations yet. Not a finding:
            // that is the system working slowly, which is what it is supposed to do.
            return;
        }
        found.add(
                new Discrepancy(
                        withdrawal.getId(),
                        Discrepancy.Kind.BROADCAST_BUT_NOT_MINED,
                        "broadcast more than " + properties.stuckAfter() + " ago and the chain has no "
                                + "receipt; the signer resends, but it never reprices"));
    }

    /**
     * A withdrawal that has been approved and never left. Is anything still working on it?
     *
     * <p>No chain call, because there is no transaction to ask about — and that is precisely the
     * condition being reported. Everything else here compares the ledger against the chain; this one
     * compares the ledger against the clock, because the failure is that nothing ever reached the
     * chain at all.
     *
     * <p><b>Why this needs checking rather than being someone else's problem.</b> The path from
     * APPROVED to BROADCAST is two hops with no retry of last resort behind either. The outbox relay
     * halts its batch on a failed send, so one unsendable row holds up everything behind it. The
     * signer's listener makes live JSON-RPC calls inside its transaction and gets three attempts over
     * about a second and a half before the message is dead-lettered, and nothing consumes that topic.
     * Neither failure emits anything, and the client's funds are held throughout — so without this,
     * the one report that exists to answer "is anything stuck?" returned a clean bill of health while
     * a balance sat locked up indefinitely.
     *
     * <p>Reusing {@code chain.stuck-after} rather than adding a second budget. The two stalls it now
     * governs have very different normal latencies — signing takes about a second, mining takes as
     * long as it takes — so a single threshold is generous for this one. Generous is the right
     * direction: this is a report a person reads, and a second knob to tune is a second knob to get
     * wrong.
     */
    private void checkStillBeingSigned(Withdrawal withdrawal, Instant stuckBefore, List<Discrepancy> found) {
        if (!withdrawal.getUpdatedAt().isBefore(stuckBefore)) {
            return;
        }
        found.add(
                new Discrepancy(
                        withdrawal.getId(),
                        Discrepancy.Kind.APPROVED_BUT_NEVER_SIGNED,
                        "approved more than " + properties.stuckAfter() + " ago and never broadcast; "
                                + "either the outbox did not publish it or the signer dead-lettered it, "
                                + "and nothing retries either on its own"));
    }

    /**
     * What the hot wallet holds, or nothing if no address is configured.
     *
     * <p>Empty rather than an exception: a deployment that has not told custody-api the signer's
     * address is missing a nice-to-have, not a correctness property, and a reconciliation run that
     * refused to produce any findings because of it would be trading the useful checks for the
     * decorative one.
     */
    private Optional<BigInteger> hotWalletBalance() {
        if (properties.hotWalletAddress().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(chain.balanceOf(properties.hotWalletAddress()));
    }
}
