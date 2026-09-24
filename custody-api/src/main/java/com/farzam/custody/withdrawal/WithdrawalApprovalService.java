package com.farzam.custody.withdrawal;

import com.farzam.custody.outbox.OutboxWriter;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalApproved;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a withdrawal to {@code APPROVED} and tells the signer, atomically.
 *
 * <p>The transition and its announcement, and nothing else. Who is allowed to approve, whether their
 * signatures verify and how many of them an amount needs are all
 * {@link com.farzam.custody.approval.ApprovalService}'s business, and it calls this once it has an
 * answer. M4's dev endpoint reaches the same method with an empty list, which is why the outbox, the
 * relay and the signer could be built and demonstrated before the approval machinery existed.
 *
 * <p>Keeping the two apart is what lets the dev endpoint stay honest. It can skip the approvals but
 * it cannot fabricate them, so the event it produces carries none and the signer refuses it —
 * bypassing the approval endpoint does not bypass the approvals.
 *
 * <p>There is no second bean here, unlike {@code WithdrawalWriter} next to {@code WithdrawalService}.
 * That split exists so a caller can react to its own transaction failing; nothing here needs to.
 * An approval either happens or it does not.
 */
@Service
public class WithdrawalApprovalService {

    private final WithdrawalRepository withdrawals;
    private final OutboxWriter outbox;

    WithdrawalApprovalService(WithdrawalRepository withdrawals, OutboxWriter outbox) {
        this.withdrawals = withdrawals;
        this.outbox = outbox;
    }

    /**
     * Approves a withdrawal, and queues the event that sends it to the signer.
     *
     * <p><b>One transaction, two writes, no gap.</b> The status change and the outbox row commit
     * together. There is no moment at which the database says a withdrawal is approved but nothing
     * will ever tell the signer, and none at which the signer has been told about an approval that
     * was rolled back.
     *
     * <p><b>Two approvals arriving at once produce one event.</b> The approval path takes
     * {@code SELECT … FOR UPDATE} on the withdrawal before it counts, so the second caller waits and
     * sees a settled count rather than racing. Behind that, the {@code @Version} column is still the
     * backstop for any other route to this method: both transactions would move the withdrawal to
     * {@code APPROVED} and write an outbox row, and the second to commit would fail on the version
     * check, taking its outbox row down with it. The guarantee comes from the row being written
     * inside the same transaction as the state change; a publish that happened after the commit
     * would already be gone by the time the conflict was discovered.
     *
     * <p><b>Approving twice in sequence is a {@code 409}, not a second event.</b> The state machine
     * has no {@code APPROVED → APPROVED} edge, so {@link Withdrawal#moveTo} refuses. Idempotency here
     * is the state machine's job rather than a key's: an approval is not a request a client retries
     * blindly, and the honest answer to "approve this again" is that it has already happened.
     *
     * <p>Returns an {@link Optional} rather than throwing on a missing withdrawal, for the reason
     * given on {@link com.farzam.custody.web.NotFoundException}: whether absence is a {@code 404} is
     * the controller's decision to make, not this method's.
     *
     * @param withdrawalId which withdrawal
     * @param approvals the evidence to publish with it. The signer re-verifies every entry against
     *     its own trusted keys, so this list is a claim rather than a credential — an empty one is
     *     valid and produces an event the signer will refuse.
     * @return the withdrawal, now approved, or empty if there is no such withdrawal
     * @throws IllegalStateTransitionException if it is not waiting for approval
     */
    @Transactional
    public Optional<Withdrawal> approve(UUID withdrawalId, List<WithdrawalApproved.Approval> approvals) {
        Optional<Withdrawal> found = withdrawals.findById(withdrawalId);
        found.ifPresent(withdrawal -> approveAndAnnounce(withdrawal, approvals));
        return found;
    }

    private void approveAndAnnounce(Withdrawal withdrawal, List<WithdrawalApproved.Approval> approvals) {
        withdrawal.moveTo(WithdrawalStatus.APPROVED);
        outbox.append(
                Topics.WITHDRAWALS,
                EventType.WITHDRAWAL_APPROVED,
                withdrawal.getId(),
                new WithdrawalApproved(
                        withdrawal.getId(),
                        withdrawal.getDestination(),
                        withdrawal.getAmount(),
                        approvals));
        // No save(). The withdrawal was loaded inside this transaction, so it is a managed entity and
        // Hibernate writes the UPDATE at commit. Calling save() would be a no-op that suggests
        // otherwise.
    }
}
