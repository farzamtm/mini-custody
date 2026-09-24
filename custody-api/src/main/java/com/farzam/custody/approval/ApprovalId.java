package com.farzam.custody.approval;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The primary key of {@link Approval}.
 *
 * <p>A plain class rather than a record, for the reason given on
 * {@link com.farzam.custody.whitelist.WhitelistedAddressId}: JPA's {@code @IdClass} needs a public
 * no-arg constructor, which a record cannot have.
 */
public class ApprovalId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private UUID withdrawalId;
    private UUID approverId;

    public ApprovalId() {}

    public ApprovalId(UUID withdrawalId, UUID approverId) {
        this.withdrawalId = withdrawalId;
        this.approverId = approverId;
    }

    public UUID getWithdrawalId() {
        return withdrawalId;
    }

    public UUID getApproverId() {
        return approverId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ApprovalId that && Objects.equals(withdrawalId, that.withdrawalId)
                && Objects.equals(approverId, that.approverId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(withdrawalId, approverId);
    }
}
