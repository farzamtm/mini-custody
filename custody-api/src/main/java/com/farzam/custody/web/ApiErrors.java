package com.farzam.custody.web;

import com.farzam.custody.approval.AlreadyApprovedException;
import com.farzam.custody.approval.InvalidApprovalSignatureException;
import com.farzam.custody.approval.MalformedPublicKeyException;
import com.farzam.custody.approval.SelfApprovalException;
import com.farzam.custody.approval.UnknownApproverException;
import com.farzam.custody.chain.MalformedAddressException;
import com.farzam.custody.chain.RpcException;
import com.farzam.custody.ledger.InsufficientFundsException;
import com.farzam.custody.ledger.LedgerContentionException;
import com.farzam.custody.ledger.UnknownAccountException;
import com.farzam.custody.whitelist.AddressNotWhitelistedException;
import com.farzam.custody.withdrawal.IdempotencyKeyReusedException;
import com.farzam.custody.withdrawal.IllegalStateTransitionException;
import com.farzam.custody.withdrawal.NotAClientAccountException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error this API can return, in one file and one format.
 *
 * <p>The format is RFC 9457 Problem Details, which Spring models as {@link ProblemDetail} and
 * serialises as {@code application/problem+json}. One shape for every failure means a client writes
 * one error path instead of guessing at whatever each endpoint happens to emit.
 *
 * <p><b>Clients branch on {@code code}, not on {@code status}.</b> Three quite different situations
 * return {@code 422} here — the address is not whitelisted, the account is wrong, there is not enough
 * money — and only the first is worth showing a user a "add this address" button for. The status code
 * says how to treat the response; the code says what happened. {@code detail} is prose, for whoever
 * is reading a log at two in the morning, and clients should not parse it.
 *
 * <p><b>What deliberately does not appear in a response.</b> Not the balance, when a withdrawal is
 * short — the number is in the exception, because that is where the decision was made, and it stays
 * there. Not the idempotency key, and not the request it was first used for. Not a stack trace, ever.
 * This API has no authentication yet (M2 is about the contract, not the perimeter), so every error
 * body has to be written as though a stranger is reading it, because one might be.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} rather than starting from nothing brings
 * Spring's own handling of the framework-level failures — an unreadable body, a wrong content type,
 * a missing header — already shaped as problem documents. What is overridden below is only the two
 * validation cases, and only to attach a {@code code} and to list the fields that failed.
 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    // ---- 404 ---------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException failure) {
        return problem(HttpStatus.NOT_FOUND, "Not found", failure.code(), failure.getMessage());
    }

    // ---- 409 ---------------------------------------------------------------

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException failure) {
        return problem(HttpStatus.CONFLICT, "Idempotency key reused", "IDEMPOTENCY_KEY_REUSED", failure.getMessage());
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail illegalTransition(IllegalStateTransitionException failure) {
        // Worth a warning rather than a debug line: reaching this means two parts of the system
        // disagree about where a withdrawal is, which is not a client's mistake.
        LOG.warn("illegal withdrawal state transition", failure);
        return problem(
                HttpStatus.CONFLICT,
                "Illegal state transition",
                "ILLEGAL_STATE_TRANSITION",
                failure.getMessage());
    }

    /**
     * The same approver, twice, on the same withdrawal.
     *
     * <p>Debug, not warn. An approver refreshing a page or retrying a request that timed out is an
     * ordinary thing to do, and the primary key turning it into a {@code 409} is the system working.
     */
    @ExceptionHandler(AlreadyApprovedException.class)
    ProblemDetail alreadyApproved(AlreadyApprovedException failure) {
        LOG.debug("a duplicate approval was refused", failure);
        return problem(
                HttpStatus.CONFLICT,
                "Already approved",
                "ALREADY_APPROVED",
                "this approver has already approved this withdrawal");
    }

    // ---- 422 ---------------------------------------------------------------

    /**
     * A signature that is not what it claims to be.
     *
     * <p>Logged at warn with the cause, which names the approver and the withdrawal — somebody
     * should look at a failed approval, because the honest explanations for one are a client bug and
     * an attempt to forge a sign-off, and only the log can tell them apart. The response says none of
     * it: which of the several ways a signature can fail applied here is information about how close
     * a forgery got.
     */
    @ExceptionHandler(InvalidApprovalSignatureException.class)
    ProblemDetail invalidApprovalSignature(InvalidApprovalSignatureException failure) {
        LOG.warn("an approval signature did not verify", failure);
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid approval signature",
                "INVALID_APPROVAL_SIGNATURE",
                "the signature is not this approver's signature over this withdrawal");
    }

    @ExceptionHandler(UnknownApproverException.class)
    ProblemDetail unknownApprover(UnknownApproverException failure) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Unknown approver",
                "UNKNOWN_APPROVER",
                "the request names an approver that is not registered");
    }

    /**
     * Four eyes, not two keys.
     *
     * <p>Worth a warning: it means an approver registered against a client tried to approve that
     * client's own withdrawal, which is either a misconfigured registry or somebody testing where
     * the line is. Neither should be silent.
     */
    @ExceptionHandler(SelfApprovalException.class)
    ProblemDetail selfApproval(SelfApprovalException failure) {
        LOG.warn("a self-approval was refused", failure);
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Self-approval",
                "SELF_APPROVAL",
                "an approver cannot approve a withdrawal belonging to the client they act for");
    }

    @ExceptionHandler(AddressNotWhitelistedException.class)
    ProblemDetail notWhitelisted(AddressNotWhitelistedException failure) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Address not whitelisted",
                "ADDRESS_NOT_WHITELISTED",
                "the destination is not on this client's whitelist");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException failure) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Insufficient funds",
                "INSUFFICIENT_FUNDS",
                // No numbers. The exception carries the balance and the amount, because it was
                // thrown with the row locked and those were the values the decision was made on;
                // that is for the log, not for an unauthenticated caller.
                "the account does not hold enough to cover this withdrawal");
    }

    @ExceptionHandler(UnknownAccountException.class)
    ProblemDetail unknownAccount(UnknownAccountException failure) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Unknown account",
                "UNKNOWN_ACCOUNT",
                "the request names an account that does not exist");
    }

    @ExceptionHandler(NotAClientAccountException.class)
    ProblemDetail notAClientAccount(NotAClientAccountException failure) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Not a client account",
                "NOT_A_CLIENT_ACCOUNT",
                "only a client account can request a withdrawal");
    }

    // ---- 400 ---------------------------------------------------------------

    @ExceptionHandler(MalformedAddressException.class)
    ProblemDetail malformedAddress(MalformedAddressException failure) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed address", "MALFORMED_ADDRESS", failure.getMessage());
    }

    /**
     * Bytes offered as a public key that are not one.
     *
     * <p>{@code failure.getMessage()} crosses, unlike the signature case above. A public key is
     * public and its length is not a secret, so "an Ed25519 public key is 32 bytes, got 31" is the
     * whole of what somebody registering an approver needs in order to fix their paste.
     */
    @ExceptionHandler(MalformedPublicKeyException.class)
    ProblemDetail malformedPublicKey(MalformedPublicKeyException failure) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed public key", "MALFORMED_PUBLIC_KEY", failure.getMessage());
    }

    /**
     * A request body that failed Bean Validation — the constraints the contract's schema generated.
     *
     * <p>Overridden only to add the {@code code} and the per-field list. A client that sent an
     * amount of {@code "0"} deserves to be told which field was wrong, not just that something was.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException failure,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "VALIDATION_FAILED",
                "the request body is not valid");
        body.setProperty(
                "errors",
                failure.getBindingResult().getFieldErrors().stream().map(ApiErrors::describe).toList());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A constraint on a method parameter rather than on a body — here, the length of
     * {@code Idempotency-Key}.
     *
     * <p>Spring raises this when it validates the handler method itself, and the default response
     * says almost nothing. Clients should not have to care which of Spring's two validation
     * mechanisms caught them, so this returns the same shape as a body failure.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException failure,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.badRequest().body(parameterProblem());
    }

    /**
     * The other half of parameter validation.
     *
     * <p>The generated API interfaces carry {@code @Validated}, which puts a validating proxy in
     * front of the controller — and that proxy throws Bean Validation's own
     * {@link ConstraintViolationException} rather than Spring MVC's
     * {@link HandlerMethodValidationException}. Without this handler an {@code Idempotency-Key} two
     * characters too short is a {@code 500}, which is both the wrong answer and the wrong person's
     * problem. Both paths are handled because which one fires depends on how the controller happens
     * to be proxied, and that is not a detail a client should be able to observe.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException failure) {
        ProblemDetail problem = parameterProblem();
        problem.setProperty("errors", failure.getConstraintViolations().stream().map(ApiErrors::describe).toList());
        return problem;
    }

    // ---- 502 ---------------------------------------------------------------

    /**
     * The chain could not be reached, so the question was not answered.
     *
     * <p>{@code 502 Bad Gateway} rather than a {@code 200} with an empty report, and the distinction
     * is the same one the confirmation watcher makes: a node that cannot be asked has said nothing,
     * and "no answer" must never read as "the chain agrees". A reconciliation endpoint that returned
     * a clean bill of health when its only source of truth was unreachable would be worse than one
     * that did not exist.
     *
     * <p>Only {@code GET /v1/reconciliation} can reach this. The watcher's own RPC failures never
     * touch the web layer — they roll a scheduled transaction back and are retried on the next tick.
     */
    @ExceptionHandler(RpcException.class)
    ProblemDetail chainUnreachable(RpcException failure) {
        LOG.warn("a request needed the chain and could not reach it", failure);
        return problem(
                HttpStatus.BAD_GATEWAY,
                "Chain unreachable",
                "CHAIN_UNREACHABLE",
                // The node's own message can name an internal hostname or a provider's API key in a
                // URL. The type is enough for a client; the cause is in the log.
                "the Ethereum node could not be reached, so this question was not answered");
    }

    // ---- 503 ---------------------------------------------------------------

    /**
     * The ledger lost too many optimistic races in a row.
     *
     * <p>{@code 503} with {@code Retry-After}, not {@code 500}. Nothing is broken: the account is
     * simply busier than the retry budget allows, and the request will very likely succeed if it is
     * sent again. A {@code 500} would tell the client to give up and page somebody.
     *
     * <p>Only reachable under {@code ledger.locking=optimistic}; the default strategy makes a
     * contending writer wait rather than fail. See ADR 0001.
     */
    @ExceptionHandler(LedgerContentionException.class)
    ResponseEntity<ProblemDetail> ledgerBusy(LedgerContentionException failure) {
        LOG.warn("ledger contention exhausted the retry budget", failure);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(
                        problem(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "Ledger busy",
                                "LEDGER_BUSY",
                                "the account is under contention; retry shortly"));
    }

    // ---- Shared ------------------------------------------------------------

    private static ProblemDetail parameterProblem() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "VALIDATION_FAILED",
                "a request parameter or header is not valid");
    }

    private static Map<String, String> describe(FieldError error) {
        String message = error.getDefaultMessage();
        return Map.of("field", error.getField(), "message", message == null ? "is not valid" : message);
    }

    /**
     * Names the parameter rather than the whole path.
     *
     * <p>A violation's property path is {@code requestWithdrawal.idempotencyKey} — method then
     * parameter. The method name is an internal detail of this server, so only the last node crosses
     * the wire. That it says {@code idempotencyKey} at all depends on the generated interface being
     * compiled with {@code -parameters}; see {@code custody-api/build.gradle.kts}.
     */
    private static Map<String, String> describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return Map.of("field", path.substring(path.lastIndexOf('.') + 1), "message", violation.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        // An extension member, which Jackson writes as a top-level field alongside `status` and
        // `detail`. RFC 9457 reserves the core names and leaves the rest of the object to the API.
        problem.setProperty("code", code);
        return problem;
    }
}
