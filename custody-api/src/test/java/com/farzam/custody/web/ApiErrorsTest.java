package com.farzam.custody.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.chain.MalformedAddressException;
import com.farzam.custody.chain.RpcException;
import com.farzam.custody.ledger.AccountType;
import com.farzam.custody.ledger.InsufficientFundsException;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.LedgerContentionException;
import com.farzam.custody.ledger.UnknownAccountException;
import com.farzam.custody.whitelist.AddressNotWhitelistedException;
import com.farzam.custody.withdrawal.IdempotencyKeyReusedException;
import com.farzam.custody.withdrawal.IllegalStateTransitionException;
import com.farzam.custody.withdrawal.NotAClientAccountException;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The exception-to-status-and-code table, asserted directly.
 *
 * <p>{@code WithdrawalApiIntegrationTest} already drives most of these through real HTTP, which is
 * the better test where it is possible. It is not possible for all of them: an illegal state
 * transition only becomes reachable over the wire in M3, and ledger contention needs the optimistic
 * strategy under load. Both are in the contract already — it lists the {@code 409} and the
 * {@code 503} — so they are pinned down here rather than left until something happens to exercise
 * them.
 *
 * <p>No Spring context: the advice is a plain object and its handlers are plain methods.
 */
class ApiErrorsTest {

    private final ApiErrors errors = new ApiErrors();

    @Test
    void aMissingResourceIsAFourOhFourCarryingItsOwnCode() {
        assertThat(errors.notFound(NotFoundException.account(UUID.randomUUID())))
                .satisfies(problem -> assertStatusAndCode(problem, HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
        assertThat(errors.notFound(NotFoundException.withdrawal(UUID.randomUUID())))
                .satisfies(problem -> assertStatusAndCode(problem, HttpStatus.NOT_FOUND, "WITHDRAWAL_NOT_FOUND"));
    }

    @Test
    void aReusedIdempotencyKeyIsAConflict() {
        assertStatusAndCode(
                errors.idempotencyKeyReused(new IdempotencyKeyReusedException()),
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void anIllegalTransitionIsAConflictAndNamesBothStates() {
        ProblemDetail problem = errors.illegalTransition(
                new IllegalStateTransitionException(WithdrawalStatus.CONFIRMED, WithdrawalStatus.APPROVED));

        assertStatusAndCode(problem, HttpStatus.CONFLICT, "ILLEGAL_STATE_TRANSITION");
        assertThat(problem.getDetail()).contains("CONFIRMED").contains("APPROVED");
    }

    /**
     * A node that could not be reached is a {@code 502}, and the node's own words do not cross.
     *
     * <p>{@code 502} rather than a {@code 200} with an empty report, for the reason the confirmation
     * watcher gives: "the node did not answer" must never read as "the chain agrees". The message
     * stays in the log, because a JSON-RPC failure can name an internal hostname or carry a
     * provider's API key in a URL.
     */
    @Test
    void anUnreachableChainIsABadGatewayAndSaysNothingAboutTheNode() {
        ProblemDetail problem = errors.chainUnreachable(new RpcException("could not reach https://node.internal:8545"));

        assertStatusAndCode(problem, HttpStatus.BAD_GATEWAY, "CHAIN_UNREACHABLE");
        assertThat(problem.getDetail()).doesNotContain("node.internal");
    }

    @Test
    void theThreeUnprocessableCasesAreToldApartByCodeAndNotByStatus() {
        assertStatusAndCode(
                errors.notWhitelisted(new AddressNotWhitelistedException(UUID.randomUUID(), "0xabc")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "ADDRESS_NOT_WHITELISTED");
        assertStatusAndCode(
                errors.unknownAccount(new UnknownAccountException(UUID.randomUUID())),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "UNKNOWN_ACCOUNT");
        assertStatusAndCode(
                errors.notAClientAccount(new NotAClientAccountException(UUID.randomUUID(), AccountType.EXTERNAL)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "NOT_A_CLIENT_ACCOUNT");
    }

    @Test
    void anInsufficientFundsResponseDoesNotSayHowMuchIsThere() {
        UUID account = UUID.randomUUID();
        BigInteger balance = new BigInteger("400000000000000000");
        BigInteger requested = new BigInteger("1000000000000000000");

        ProblemDetail problem = errors.insufficientFunds(new InsufficientFundsException(account, balance, requested));

        assertStatusAndCode(problem, HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS");
        // The exception knows the numbers because it was thrown with the row locked. The response is
        // read by whoever sent the request, which — with no authentication on this API — is anybody.
        assertThat(problem.getDetail()).doesNotContain(balance.toString()).doesNotContain(account.toString());
    }

    @Test
    void aMalformedAddressIsABadRequest() {
        assertStatusAndCode(
                errors.malformedAddress(new MalformedAddressException("nope")),
                HttpStatus.BAD_REQUEST,
                "MALFORMED_ADDRESS");
    }

    @Test
    void ledgerContentionIsRetryableRatherThanBroken() {
        ResponseEntity<ProblemDetail> response = errors
                .ledgerBusy(new LedgerContentionException(JournalKind.WITHDRAWAL_HOLD, UUID.randomUUID(), 50, null));

        // 503 and not 500: nothing is wrong, the account is just busy, and the client should come
        // back rather than page somebody.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertStatusAndCode(response.getBody(), HttpStatus.SERVICE_UNAVAILABLE, "LEDGER_BUSY");
    }

    private static void assertStatusAndCode(ProblemDetail problem, HttpStatus status, String code) {
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(status.value());
        assertThat(problem.getTitle()).isNotBlank();
        assertThat(problem.getDetail()).isNotBlank();
        assertThat(problem.getProperties()).containsEntry("code", code);
    }
}
