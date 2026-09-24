package com.farzam.custody.approval;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The approvals collected against a withdrawal. */
public interface ApprovalRepository extends JpaRepository<Approval, ApprovalId> {

    /**
     * Every approval on one withdrawal, oldest first.
     *
     * <p>Ordered so the evidence list on a {@code WithdrawalApproved} is stable: the same set of
     * approvals always produces the same event body, which is what makes two deliveries of it
     * comparable and a diff of two events readable. The signer does not care about the order — it
     * counts a set of ids — but a payload whose field order depends on how Postgres felt about the
     * query plan is one nobody can reason about.
     *
     * @param withdrawalId which withdrawal
     * @return its approvals, in the order they were given
     */
    List<Approval> findByWithdrawalIdOrderByCreatedAtAsc(UUID withdrawalId);

    /**
     * Whether this approver has already signed off on this withdrawal.
     *
     * <p>{@code exists…} rather than {@code find…}: it is a {@code select 1} answered from the
     * primary-key index, and there is nothing in the row the caller wants.
     *
     * <p>This is a check, not the guarantee. The guarantee is the primary key — see
     * {@link ApprovalService#submit} for why the look-up is nonetheless safe here.
     *
     * @param withdrawalId which withdrawal
     * @param approverId which approver
     * @return true if an approval from them is already recorded
     */
    boolean existsByWithdrawalIdAndApproverId(UUID withdrawalId, UUID approverId);
}
