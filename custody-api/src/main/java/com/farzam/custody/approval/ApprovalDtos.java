package com.farzam.custody.approval;

import com.farzam.custody.api.model.ApprovalDto;
import com.farzam.custody.api.model.ApproverDto;
import com.farzam.custody.api.model.WithdrawalStatusDto;

/**
 * Turns what the approval path produced into what the contract promises.
 *
 * <p>Note what does not cross in either direction. An approval's signature is never returned: it is
 * already the caller's own, and echoing stored signatures back would make the endpoint a way to
 * collect other approvers' evidence. Nor is an approver's public key, for the same reason
 * {@code WithdrawalDtos} withholds the idempotency key — a registry that reads keys out is one more
 * way to enumerate who can authorise a payment.
 */
final class ApprovalDtos {

    private ApprovalDtos() {}

    static ApprovalDto of(ApprovalService.ApprovalOutcome outcome) {
        return new ApprovalDto(
                outcome.withdrawal().getId(),
                outcome.approverId(),
                outcome.collected(),
                outcome.required(),
                WithdrawalStatusDto.fromValue(outcome.withdrawal().getStatus().name()));
    }

    static ApproverDto of(Approver approver) {
        return new ApproverDto(approver.getId(), approver.getName()).clientId(approver.getClientId());
    }
}
