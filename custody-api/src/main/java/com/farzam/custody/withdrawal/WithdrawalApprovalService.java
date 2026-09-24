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
 * <p>This is the seam M3 plugs into. When the approvals endpoint exists it will verify Ed25519
 * signatures, reject self-approval and count the quorum, and then call {@link #approve} — the
 * transition and its announcement do not change, only the decision in front of them does. M4 reaches
 * it through a dev-profile endpoint instead, so the outbox, the relay and the signer can be built
 * and demonstrated before the approval machinery exists.
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
     * <p><b>Two approvals arriving at once produce one event.</b> Both transactions read the
     * withdrawal in {@code PENDING_APPROVAL}, both move it to {@code APPROVED}, and both write an
     * outbox row — and then the second one to commit fails on the {@code @Version} column, taking
     * its outbox row down with it. The guarantee comes from the row being written inside the same
     * transaction as the state change; a publish that happened after the commit would already be
     * gone by the time the conflict was discovered.
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
     * @return the withdrawal, now approved, or empty if there is no such withdrawal
     * @throws IllegalStateTransitionException if it is not waiting for approval
     */
    @Transactional
    public Optional<Withdrawal> approve(UUID withdrawalId) {
        Optional<Withdrawal> found = withdrawals.findById(withdrawalId);
        found.ifPresent(this::approveAndAnnounce);
        return found;
    }

    private void approveAndAnnounce(Withdrawal withdrawal) {
        withdrawal.moveTo(WithdrawalStatus.APPROVED);
        outbox.append(
                Topics.WITHDRAWALS,
                EventType.WITHDRAWAL_APPROVED,
                withdrawal.getId(),
                new WithdrawalApproved(
                        withdrawal.getId(),
                        withdrawal.getDestination(),
                        withdrawal.getAmount(),
                        // Empty until M3. The signer's policy already refuses an event whose
                        // approvals do not verify against its own trusted keys, so it will refuse
                        // every event this milestone produces — which is the correct behaviour for a
                        // system where nobody has actually approved anything yet, and is why M5 can
                        // be built against this without weakening it.
                        List.of()));
        // No save(). The withdrawal was loaded inside this transaction, so it is a managed entity and
        // Hibernate writes the UPDATE at commit. Calling save() would be a no-op that suggests
        // otherwise.
    }
}
