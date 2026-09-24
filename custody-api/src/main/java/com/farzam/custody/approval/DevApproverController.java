package com.farzam.custody.approval;

import com.farzam.custody.api.DevApproversApi;
import com.farzam.custody.api.model.ApproverDto;
import com.farzam.custody.api.model.ApproverRequestDto;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /dev/approvers} — put a public key in the registry.
 *
 * <p>{@code @Profile("dev")} is the whole safety story, and the argument is the one on
 * {@code DevDepositController}: a profile is not a flag checked per request. Without it the bean is
 * never created, the handler is never registered, and the path returns a {@code 404} like any other
 * URL that does not exist.
 *
 * <p>It needs that, because an unauthenticated endpoint that adds an approver is an unauthenticated
 * endpoint that manufactures a quorum. What bounds it even so is the thing it cannot reach: the
 * signer's trusted keys are configuration, not this table, so a key registered here satisfies this
 * service and is refused by the one holding the private keys. That is the same argument that makes
 * {@code POST /dev/withdrawals/{id}/approve} survivable, and it is not an accident that both dev
 * endpoints on the money path have it.
 *
 * <p>What replaces this in a real deployment is not another endpoint. Approvers are people, and
 * adding one is an onboarding process with a human decision in it — the code that would survive is
 * {@link ApproverService}, called from wherever that process ends up.
 */
@RestController
@Profile("dev")
class DevApproverController implements DevApproversApi {

    private final ApproverService approvers;

    DevApproverController(ApproverService approvers) {
        this.approvers = approvers;
    }

    @Override
    public ResponseEntity<ApproverDto> registerApprover(ApproverRequestDto request) {
        // getClientId() is a plain nullable UUID rather than an Optional: `openApiNullable` is off,
        // and a nullable field with no default generates as the bare type. Null is the ordinary
        // case here — custodian staff act for nobody — so it is passed straight through.
        Approver approver = approvers.register(request.getName(), request.getPublicKey(), request.getClientId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalDtos.of(approver));
    }
}
