package com.farzam.custody.withdrawal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Withdrawals by id, by the key their client chose for them, and by what the watcher has to check. */
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

    /**
     * The withdrawals the confirmation watcher has to ask the chain about, claimed for this
     * transaction alone.
     *
     * <p><b>{@code SKIP LOCKED} is what makes a second instance safe</b>, and it is the same
     * argument the outbox relay's query makes. Plain {@code FOR UPDATE} would make a second watcher
     * block on the first one's rows and then process them again once it got them; skipping locked
     * rows gives each watcher a disjoint batch instead. The hint value {@code -2} is Hibernate's
     * spelling of {@code SKIP_LOCKED} — JPA has no portable constant for it, which is why this is a
     * magic number with a comment rather than a symbol.
     *
     * <p>Ordered by {@code updatedAt}, so the withdrawal that has been waiting longest is looked at
     * first and a backlog drains in the order it built up rather than in whatever order the planner
     * finds convenient.
     *
     * @param limit how many to claim; the rows stay locked for the length of the batch, so this
     *     trades latency against lock time
     * @return the claimed withdrawals, as managed entities whose status changes are written at commit
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select w from Withdrawal w where w.status = com.farzam.custody.withdrawal.WithdrawalStatus.BROADCAST"
            + " order by w.updatedAt")
    List<Withdrawal> findBroadcastForUpdateSkipLocked(Limit limit);

    /**
     * Every withdrawal in a given state, for reconciliation to check against the chain.
     *
     * <p>No lock and no limit. Reconciliation reads; it changes nothing, and it is meant to see the
     * whole picture rather than a batch of it. At this project's volume that is a sequential scan of
     * a small table behind {@code withdrawals_status}; a real one would page.
     *
     * @param status which state
     * @return the withdrawals in it
     */
    List<Withdrawal> findByStatus(WithdrawalStatus status);
}
