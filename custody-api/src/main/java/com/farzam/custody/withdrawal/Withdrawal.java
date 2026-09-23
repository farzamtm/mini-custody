package com.farzam.custody.withdrawal;

import com.farzam.custody.chain.EthereumAddress;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A client's request to send ETH somewhere, and everything that has happened to it since.
 *
 * <p>The entity owns its own state machine. {@link #moveTo} is the only way the status changes and
 * it asks {@link WithdrawalStatus#canMoveTo} first, so an illegal move is impossible to write rather
 * than merely discouraged — the same rich-domain-model idea as a Doctrine entity that refuses to let
 * you set a field into an invalid combination. A setter for {@code status} would give every future
 * caller, including a Kafka listener written in M4, the ability to skip the check.
 *
 * <p>{@code final} for the reason given on {@link com.farzam.custody.ledger.Account}: the
 * constructor validates, and SpotBugs is right that a subclass could catch the throw and keep a
 * half-built object.
 */
@Entity
@Table(name = "withdrawals")
public final class Withdrawal {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private String destination;

    /**
     * In wei. {@code numeric(78,0)} in Postgres, and the check constraint on the column says
     * {@code > 0} — the contract's pattern says the same thing at the edge, and neither is a
     * substitute for the other.
     */
    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalStatus status;

    /** Client-chosen, unique per client. See {@link WithdrawalWriter} for what it buys. */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /** SHA-256 of the canonical request, so a reused key with new contents can be told apart. */
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    /** Null until the signer broadcasts (M5). */
    @Column(name = "tx_hash")
    private String txHash;

    /** Null unless the withdrawal ended in {@code REJECTED} or {@code FAILED}. */
    @Column(name = "failure_reason")
    private String failureReason;

    /** Nullable {@code Long}, so Spring Data reads "unsaved" correctly. See {@code Account}. */
    @Version
    private Long version;

    /**
     * Written in Java rather than left to the column default.
     *
     * <p>The column does default to {@code now()}, but a default only fills the value in on the
     * database side: the instance this thread is holding would still have {@code null} in it, and
     * the response is built from that instance. Setting it here means one value, known to both.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA requires a no-arg constructor; it is not part of the API. */
    Withdrawal() {}

    /**
     * A newly requested withdrawal, in {@link WithdrawalStatus#PENDING_APPROVAL}.
     *
     * <p>The id is generated here rather than by the database. It has to be: the ledger posting that
     * holds the funds uses it as the reference id, and that happens in the same transaction as the
     * insert, so the value must exist before the row does.
     *
     * @param clientId whose money is moving
     * @param accountId the client account to debit
     * @param destination where the funds are going, in any case
     * @param amount how much, in wei; must be positive
     * @param idempotencyKey the client's key for this request
     * @param requestHash SHA-256 of the canonical request, from {@link RequestHash}
     * @return the new withdrawal, not yet saved
     */
    public static Withdrawal requested(
            UUID clientId,
            UUID accountId,
            String destination,
            BigInteger amount,
            String idempotencyKey,
            String requestHash) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.id = UUID.randomUUID();
        withdrawal.clientId = Objects.requireNonNull(clientId, "clientId");
        withdrawal.accountId = Objects.requireNonNull(accountId, "accountId");
        withdrawal.destination = EthereumAddress.normalise(destination);
        withdrawal.amount = requirePositive(amount);
        withdrawal.status = WithdrawalStatus.PENDING_APPROVAL;
        withdrawal.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        withdrawal.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        withdrawal.createdAt = Instant.now();
        withdrawal.updatedAt = withdrawal.createdAt;
        return withdrawal;
    }

    /**
     * Moves the withdrawal to its next status, or refuses.
     *
     * @param next where it should go
     * @throws IllegalStateTransitionException if the move is not on the state machine
     */
    public void moveTo(WithdrawalStatus next) {
        Objects.requireNonNull(next, "next");
        if (!status.canMoveTo(next)) {
            throw new IllegalStateTransitionException(status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /**
     * Records the transaction hash the signer produced.
     *
     * @param hash the on-chain transaction hash
     */
    public void broadcastAs(String hash) {
        this.txHash = Objects.requireNonNull(hash, "hash");
        moveTo(WithdrawalStatus.BROADCAST);
    }

    /**
     * Ends the withdrawal unsuccessfully and says why.
     *
     * @param status {@link WithdrawalStatus#REJECTED} or {@link WithdrawalStatus#FAILED}
     * @param reason a short explanation, stored for the client to read
     */
    public void endWith(WithdrawalStatus status, String reason) {
        if (!status.releasesTheHold()) {
            throw new IllegalArgumentException("only REJECTED and FAILED carry a reason, not " + status);
        }
        moveTo(status);
        this.failureReason = reason;
    }

    private static BigInteger requirePositive(BigInteger amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("a withdrawal must be for a positive amount, not " + amount);
        }
        return amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getDestination() {
        return destination;
    }

    public BigInteger getAmount() {
        return amount;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getTxHash() {
        return txHash;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
