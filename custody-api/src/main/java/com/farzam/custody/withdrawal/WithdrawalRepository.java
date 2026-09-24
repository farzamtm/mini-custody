package com.farzam.custody.withdrawal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Withdrawals by id, and by the key their client chose for them. */
public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    /**
     * The idempotency look-up.
     *
     * <p>Backed by the {@code unique (client_id, idempotency_key)} index, which is also what makes
     * the look-up safe to lose a race to: see {@link WithdrawalService#request}.
     *
     * <p>Scoped by client, not global. Two clients picking the same key is not a collision — they
     * are different requests by different people — and a global key space would let one client's
     * choice of key deny another's.
     *
     * @param clientId whose withdrawals to search
     * @param idempotencyKey the key from the request header
     * @return the withdrawal that key already created, if any
     */
    Optional<Withdrawal> findByClientIdAndIdempotencyKey(UUID clientId, String idempotencyKey);

    /**
     * The same look-up as {@code findById}, with {@code SELECT … FOR UPDATE} on the row.
     *
     * <p>For a caller that is about to decide something from what it reads and then write based on
     * that decision — which is the approval path, where the decision is "does this complete the
     * quorum". The alternative is the {@code @Version} column, and that is a different guarantee:
     * optimistic locking detects the conflict after the fact and resolves it by rolling one
     * transaction back, which for an approval means discarding a signature somebody meant to give.
     * A lock makes the second caller wait and then read the truth. See
     * {@link com.farzam.custody.approval.ApprovalService#submit} and ADR 0001.
     *
     * <p>Spelled out as a query rather than as {@code @Lock} on a derived method, because the
     * derived {@code findById} is inherited from {@link JpaRepository} and annotating an override of
     * it is a quiet way to change the behaviour of every existing caller.
     *
     * @param id the withdrawal to lock
     * @return the withdrawal, with its row locked until the transaction ends
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Withdrawal w where w.id = :id")
    Optional<Withdrawal> findByIdForUpdate(@Param("id") UUID id);
}
