package com.farzam.custody.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One approver's recorded sign-off on one withdrawal.
 *
 * <p>The row is the evidence. It is not "we checked and it was fine" — the signature itself is
 * stored, so the approval can be re-verified by anyone later, including by the signer, which does
 * exactly that before it will sign. An audit trail that records a verdict rather than the thing the
 * verdict was about is an audit trail you have to trust; this one can be checked.
 *
 * <p><b>The primary key is {@code (withdrawal_id, approver_id)}, and that is the quorum rule.</b>
 * Counting approvals and counting approvers are the same number only because the database will not
 * hold two rows for one approver on one withdrawal. Without it, a two-approver quorum would be one
 * approver posting the same valid signature twice, which is a copy and a paste away and defeats four
 * eyes entirely. The signer enforces the same rule independently with a set of ids, because it does
 * not get to assume this table exists.
 *
 * <p>Nothing here is mutable. An approval is not withdrawn by editing the row — there is no path in
 * this code that updates or deletes one — for the same reason the ledger is append-only.
 *
 * <p>{@code final}, like {@link com.farzam.custody.whitelist.WhitelistedAddress} and for the same
 * reason.
 */
@Entity
@Table(name = "approvals")
@IdClass(ApprovalId.class)
public final class Approval {

    @Id
    @Column(name = "withdrawal_id", nullable = false)
    private UUID withdrawalId;

    @Id
    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    /** The raw 64-byte Ed25519 signature over the approval statement. */
    @Column(nullable = false)
    private byte[] signature;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA requires a no-arg constructor; it is not part of the API. */
    Approval() {}

    /**
     * Records a signature that has already been verified.
     *
     * <p>Verification happens in {@link ApprovalService}, not here, and the split is worth noting:
     * this constructor cannot check the signature, because doing so needs the approver's key and the
     * withdrawal's destination and amount, none of which an approval row holds. A constructor that
     * took all of them to validate one field would be a service with a misleading name.
     *
     * @param withdrawalId what was approved
     * @param approverId who approved it
     * @param signature their 64-byte signature over the statement
     * @return the new approval, not yet saved
     */
    public static Approval of(UUID withdrawalId, UUID approverId, byte[] signature) {
        Approval approval = new Approval();
        approval.withdrawalId = Objects.requireNonNull(withdrawalId, "withdrawalId");
        approval.approverId = Objects.requireNonNull(approverId, "approverId");
        approval.signature = signature.clone();
        approval.createdAt = Instant.now();
        return approval;
    }

    public UUID getWithdrawalId() {
        return withdrawalId;
    }

    public UUID getApproverId() {
        return approverId;
    }

    /**
     * @return a copy of the signature bytes, so the stored evidence cannot be altered through the
     *     reference this returns
     */
    public byte[] getSignature() {
        return signature.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
