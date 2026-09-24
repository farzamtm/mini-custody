package com.farzam.custody.approval;

import com.farzam.custody.api.ApprovalsApi;
import com.farzam.custody.api.model.ApprovalDto;
import com.farzam.custody.api.model.ApprovalRequestDto;
import com.farzam.custody.web.NotFoundException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/withdrawals/{id}/approvals} — one approver signs off.
 *
 * <p>Like every controller here, its whole job is translation: DTO to arguments on the way in,
 * outcome to DTO on the way out, and absence to {@code 404}. The decisions are in
 * {@link ApprovalService} and the failures become problem documents in
 * {@link com.farzam.custody.web.ApiErrors}, not in a try/catch here.
 */
@RestController
class ApprovalController implements ApprovalsApi {

    private final ApprovalService approvals;

    ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    /**
     * {@code 201}, because an approval is a thing that now exists.
     *
     * <p>No {@code Location} header, though: an approval has no URL of its own. There is no
     * {@code GET /v1/withdrawals/{id}/approvals}, and deliberately not — who has approved a payment
     * is exactly what an API with no authentication should not read out to whoever asks. The
     * response says how far the quorum has got, which is what the approver needs and nothing more.
     */
    @Override
    public ResponseEntity<ApprovalDto> approveWithdrawal(UUID withdrawalId, ApprovalRequestDto request) {
        ApprovalService.ApprovalOutcome outcome = approvals
                .submit(withdrawalId, request.getApproverId(), request.getSignature())
                .orElseThrow(() -> NotFoundException.withdrawal(withdrawalId));

        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalDtos.of(outcome));
    }
}
