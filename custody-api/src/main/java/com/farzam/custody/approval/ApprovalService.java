package com.farzam.custody.approval;

import com.farzam.crypto.Ed25519;
import com.farzam.custody.withdrawal.IllegalStateTransitionException;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import com.farzam.events.ApprovalStatement;
import com.farzam.events.WithdrawalApproved;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one approver's sign-off, and approves the withdrawal once enough of them have.
 *
 * <p>This is the decision M4's dev endpoint was standing in for. What arrives is an approver id and
 * a signature; what has to come out the other end is a withdrawal that either has its quorum and an
 * event on its way to the signer, or does not and is still waiting.
 *
 * <p><b>What the approver signs is not this request.</b> It is an {@link ApprovalStatement} — the
 * withdrawal id, the destination and the amount — rebuilt here from the withdrawal row rather than
 * taken from the request body. A caller therefore cannot present a signature over terms of their own
 * choosing and have it recorded against a withdrawal with different ones: the only statement that
 * verifies is the one describing the payment as this service holds it. The signer rebuilds the same
 * statement from the event and checks every signature again, so the two places the terms could be
 * tampered with — this table and the topic — are both covered.
 *
 * <p><b>This service is not the security boundary, and is not written as though it were.</b> Every
 * check below is repeated in the signer against its own configuration, because an attacker who owns
 * custody-api can write these rows directly and never call this method. What this class buys is that
 * an honest system refuses early, with an error a client can act on, instead of holding a client's
 * funds for however long it takes a refusal to come back from the signer.
 */
@Service
public class ApprovalService {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalService.class);

    private final WithdrawalRepository withdrawals;
    private final ApproverRepository approvers;
    private final ApprovalRepository approvals;
    private final WithdrawalApprovalService withdrawalApprovals;
    private final ApprovalProperties properties;

    ApprovalService(WithdrawalRepository withdrawals, ApproverRepository approvers, ApprovalRepository approvals,
            WithdrawalApprovalService withdrawalApprovals, ApprovalProperties properties) {
        this.withdrawals = withdrawals;
        this.approvers = approvers;
        this.approvals = approvals;
        this.withdrawalApprovals = withdrawalApprovals;
        this.properties = properties;
    }

    /**
     * Verifies an approval, records it, and approves the withdrawal if that completes the quorum.
     *
     * <p><b>The withdrawal row is locked for the whole of it.</b> Two approvals arriving at the same
     * instant on a withdrawal that needs two is the case that matters: without the lock both
     * transactions read one existing approval, both conclude the quorum is short, and a withdrawal
     * with two valid approvals sits waiting for a third that nobody is going to send. The
     * {@code @Version} column would catch the opposite race — both concluding the quorum was met and
     * both publishing — but it catches it by rolling one of them back, which throws away a perfectly
     * good approval and asks the approver to sign again. {@code SELECT … FOR UPDATE} makes the second
     * transaction wait for the first instead, so it reads a settled count and both signatures are
     * kept. The lock is held across an Ed25519 verification, which is tens of microseconds; ADR 0001
     * covers why this codebase reaches for pessimistic locking in the first place.
     *
     * <p><b>The order of the checks is deliberate.</b> The signature is verified before anything is
     * said about self-approval or about who has already approved, so that every fact this endpoint
     * discloses beyond "that approver is unknown" costs a valid signature to obtain. There is no
     * authentication on this API yet, so an error message is a reply to a stranger: "that approver
     * has already approved this withdrawal" is a useful thing to learn about somebody else's payment
     * and should not be free.
     *
     * <p>Returns an {@link Optional} rather than throwing on a missing withdrawal, for the reason
     * given on {@link com.farzam.custody.web.NotFoundException}: whether absence is a {@code 404} is
     * the controller's decision.
     *
     * @param withdrawalId which withdrawal is being approved
     * @param approverId who is approving it
     * @param signatureBase64 their Ed25519 signature over the approval statement, base64
     * @return what the approval did, or empty if there is no such withdrawal
     * @throws IllegalStateTransitionException if the withdrawal is not waiting for approval
     * @throws UnknownApproverException if the approver is not in the registry
     * @throws InvalidApprovalSignatureException if the signature is not theirs over this withdrawal
     * @throws SelfApprovalException if the approver acts for the withdrawal's own client
     * @throws AlreadyApprovedException if they have already approved it
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "Two UUIDs and an int. Jackson has already parsed both ids as UUIDs, "
                    + "and a UUID cannot contain a newline.")
    @Transactional
    public Optional<ApprovalOutcome> submit(UUID withdrawalId, UUID approverId, String signatureBase64) {
        Optional<Withdrawal> found = withdrawals.findByIdForUpdate(withdrawalId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Withdrawal withdrawal = found.get();

        if (withdrawal.getStatus() != WithdrawalStatus.PENDING_APPROVAL) {
            // Expressed as the transition it would have to be, so it reuses the state machine's own
            // vocabulary and the 409 handler that already exists for it. An approval on a withdrawal
            // the signer has already broadcast is not a validation failure, it is a request to do
            // something the state machine has no edge for.
            throw new IllegalStateTransitionException(withdrawal.getStatus(), WithdrawalStatus.APPROVED);
        }

        Approver approver = approvers.findById(approverId).orElseThrow(() -> new UnknownApproverException(approverId));

        byte[] signature = decode(signatureBase64);
        ApprovalStatement statement = statementFor(withdrawal);
        if (!Ed25519.verify(approver.verificationKey(), statement.canonicalBytes(), signature)) {
            throw new InvalidApprovalSignatureException(approverId, withdrawalId);
        }

        if (approver.actsFor(withdrawal.getClientId())) {
            throw new SelfApprovalException(approverId, withdrawal.getClientId());
        }
        if (approvals.existsByWithdrawalIdAndApproverId(withdrawalId, approverId)) {
            // Safe as a look-up rather than as a caught unique violation, unlike the idempotency key
            // in WithdrawalService: the withdrawal row is locked, so no concurrent transaction can
            // insert an approval for it between this query and the save below. The primary key is
            // still there as the guarantee.
            throw new AlreadyApprovedException(approverId, withdrawalId);
        }

        // saveAndFlush, not save. The count below is a query against the same table, and while
        // Hibernate would flush before it anyway, depending on that is depending on a setting: a
        // context with FlushMode.COMMIT would count the approvals without this one and quietly never
        // reach quorum.
        approvals.saveAndFlush(Approval.of(withdrawalId, approverId, signature));

        List<Approval> collected = approvals.findByWithdrawalIdOrderByCreatedAtAsc(withdrawalId);
        int required = properties.requiredApprovals(withdrawal.getAmount());
        if (collected.size() >= required) {
            withdrawalApprovals.approve(withdrawalId, evidenceFrom(collected));
        }

        LOG.info(
                "approver {} approved withdrawal {}: {} of {} collected",
                approverId,
                withdrawalId,
                collected.size(),
                required);
        return Optional.of(new ApprovalOutcome(withdrawal, approverId, collected.size(), required));
    }

    /**
     * What an approver of this withdrawal is agreeing to.
     *
     * <p>Built from the stored withdrawal, never from the request. That is the whole of why a
     * signature means anything here.
     */
    private static ApprovalStatement statementFor(Withdrawal withdrawal) {
        return new ApprovalStatement(withdrawal.getId(), withdrawal.getDestination(), withdrawal.getAmount());
    }

    /**
     * The approvals as the signer will receive them: id, key and signature.
     *
     * <p>The public key travels with each approval even though the signer will not verify against it
     * — {@link WithdrawalApproved} explains why it is a claim rather than a credential. It is here so
     * the signer can say which of its own trusted keys an approval purports to be from.
     */
    private List<WithdrawalApproved.Approval> evidenceFrom(List<Approval> collected) {
        Map<UUID, Approver> byId = approvers.findByIdIn(collected.stream().map(Approval::getApproverId).toList())
                .stream()
                .collect(Collectors.toMap(Approver::getId, Function.identity()));

        Base64.Encoder base64 = Base64.getEncoder();
        return collected.stream()
                .map(
                        approval -> new WithdrawalApproved.Approval(
                                approval.getApproverId(),
                                base64.encodeToString(byId.get(approval.getApproverId()).getPublicKey()),
                                base64.encodeToString(approval.getSignature())))
                .toList();
    }

    /**
     * Base64 that has been through the contract's pattern but not through this method.
     *
     * <p>Returns an empty array rather than throwing, because an empty array never verifies and the
     * caller's next line is the verification. Validating the shape at the edge and then trusting it
     * here would make the service correct only for as long as the schema says what it says today.
     *
     * @return the bytes, or an empty array
     */
    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException malformed) {
            return new byte[0];
        }
    }

    /**
     * What one approval did.
     *
     * @param withdrawal the withdrawal, already moved to {@code APPROVED} if this completed the
     *     quorum
     * @param approverId who gave the approval this outcome describes
     * @param collected how many distinct approvers have now signed off
     * @param required how many this amount needs
     */
    public record ApprovalOutcome(Withdrawal withdrawal, UUID approverId, int collected, int required) {}
}
