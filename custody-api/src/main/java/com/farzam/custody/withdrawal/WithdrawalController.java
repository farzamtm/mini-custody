package com.farzam.custody.withdrawal;

import com.farzam.custody.api.WithdrawalsApi;
import com.farzam.custody.api.model.WithdrawalDto;
import com.farzam.custody.api.model.WithdrawalRequestDto;
import com.farzam.custody.web.NotFoundException;
import java.math.BigInteger;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /v1/withdrawals} — request one, read one.
 *
 * <p>Implements the generated {@link WithdrawalsApi}. The controller's whole job is translation:
 * DTO to command on the way in, entity to DTO on the way out, and absence to {@code 404}. Every
 * decision worth making is a layer down, and every failure is turned into a problem document by
 * {@link com.farzam.custody.web.ApiErrors} rather than by a try/catch here.
 */
@RestController
class WithdrawalController implements WithdrawalsApi {

    private final WithdrawalService withdrawals;
    private final ConfirmationCount confirmations;

    WithdrawalController(WithdrawalService withdrawals, ConfirmationCount confirmations) {
        this.withdrawals = withdrawals;
        this.confirmations = confirmations;
    }

    /**
     * {@code 202}, not {@code 201}.
     *
     * <p>A withdrawal takes minutes: approvals, then signing, then three confirmations. Holding the
     * HTTP connection open for that would tie up a thread on both ends and time out somewhere in the
     * middle anyway. {@code 202 Accepted} says the request is recorded and the work has started, and
     * the {@code Location} header says where to watch it happen.
     *
     * <p>A retry of an accepted request gets {@code 202} again with the same body. That is what
     * idempotency means from the client's side: it cannot tell whether its first attempt arrived,
     * and it does not need to.
     */
    @Override
    public ResponseEntity<WithdrawalDto> requestWithdrawal(String idempotencyKey, WithdrawalRequestDto request) {
        Withdrawal withdrawal = withdrawals.request(
                new WithdrawalCommand(
                        request.getAccountId(),
                        request.getDestination(),
                        new BigInteger(request.getAmountWei()),
                        idempotencyKey));

        // A relative Location. The absolute form would have to be built from the Host header, which
        // is whatever the client sent, and a reverse proxy in front of this would make it a guess at
        // best. RFC 9110 allows a relative reference and every client resolves it against the
        // request URI, which is the answer that is always right.
        return ResponseEntity.accepted()
                .location(URI.create("/v1/withdrawals/" + withdrawal.getId()))
                .body(WithdrawalDtos.of(withdrawal));
    }

    /**
     * The endpoint the {@code Location} header points at, and the one a client polls.
     *
     * <p>Which is why the confirmation count is read from the watcher's last observation rather than
     * from the chain: a client watching a withdrawal through three confirmations makes a lot of these
     * requests, and putting a JSON-RPC call behind each one would turn polling into load on the node
     * for a number that moves every twelve seconds anyway.
     */
    @Override
    public ResponseEntity<WithdrawalDto> getWithdrawal(UUID withdrawalId) {
        Withdrawal withdrawal = withdrawals.find(withdrawalId)
                .orElseThrow(() -> NotFoundException.withdrawal(withdrawalId));
        return ResponseEntity.ok(WithdrawalDtos.of(withdrawal, confirmations.forWithdrawal(withdrawalId)));
    }
}
