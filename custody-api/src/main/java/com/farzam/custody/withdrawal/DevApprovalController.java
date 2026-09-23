package com.farzam.custody.withdrawal;

import com.farzam.custody.api.DevWithdrawalsApi;
import com.farzam.custody.api.model.WithdrawalDto;
import com.farzam.custody.web.NotFoundException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /dev/withdrawals/{id}/approve} — approval with nobody approving.
 *
 * <p>{@code @Profile("dev")} is the whole safety story, and the argument is the one on
 * {@code DevDepositController}: a profile is not a flag checked per request. Without it the bean is
 * never created, the handler is never registered, and the path returns a {@code 404} like any other
 * URL that does not exist. There is no code path from a production deployment to this class short of
 * starting it with the wrong profile.
 *
 * <p>It is the more dangerous of the two dev endpoints. Fabricating a deposit invents money inside
 * the ledger, where the double-entry rule still has to hold; this one authorises real money to leave.
 * What keeps that bounded is the thing it cannot fake: the signer verifies approval signatures
 * against its own trusted keys, so the event this produces carries no approvals and the signer will
 * refuse it. Bypassing the approval endpoint does not bypass the approvals.
 *
 * <p>Replaced in M3 by the real endpoint, which will call the same
 * {@link WithdrawalApprovalService#approve} after it has verified a quorum of signatures.
 */
@RestController
@Profile("dev")
class DevApprovalController implements DevWithdrawalsApi {

    private final WithdrawalApprovalService approvals;

    DevApprovalController(WithdrawalApprovalService approvals) {
        this.approvals = approvals;
    }

    @Override
    public ResponseEntity<WithdrawalDto> simulateApproval(UUID withdrawalId) {
        Withdrawal withdrawal = approvals.approve(withdrawalId)
                .orElseThrow(() -> NotFoundException.withdrawal(withdrawalId));
        // 200 rather than 202: unlike requesting a withdrawal, approving one is finished when the
        // call returns. The event still has to be relayed, but that is this service's own bookkeeping
        // and the state the client asked about has already changed.
        return ResponseEntity.ok(WithdrawalDtos.of(withdrawal));
    }
}
