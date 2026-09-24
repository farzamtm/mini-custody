package com.farzam.custody.withdrawal;

import com.farzam.custody.api.DevWithdrawalsApi;
import com.farzam.custody.api.model.WithdrawalDto;
import com.farzam.custody.web.NotFoundException;
import java.util.List;
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
 * <p>Superseded by {@code POST /v1/withdrawals/{id}/approvals}, which reaches the same
 * {@link WithdrawalApprovalService#approve} once it has verified a quorum of real signatures. Kept
 * rather than deleted, because it is the only way to produce a {@code WithdrawalApproved} that the
 * signer should refuse, and "the signer refuses an approval nobody signed" is a property worth being
 * able to demonstrate by hand as well as in a test.
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
        // An empty list, still, now that the real endpoint exists. This one approves without any
        // approver having approved, so it has nothing to put here and must not invent anything: the
        // signer verifies signatures against its own trusted keys, and an event from this path
        // carries none, so it is refused. That is what keeps a dev endpoint that authorises real
        // money from being a way around the control it skips.
        Withdrawal withdrawal = approvals.approve(withdrawalId, List.of())
                .orElseThrow(() -> NotFoundException.withdrawal(withdrawalId));
        // 200 rather than 202: unlike requesting a withdrawal, approving one is finished when the
        // call returns. The event still has to be relayed, but that is this service's own bookkeeping
        // and the state the client asked about has already changed.
        return ResponseEntity.ok(WithdrawalDtos.of(withdrawal));
    }
}
