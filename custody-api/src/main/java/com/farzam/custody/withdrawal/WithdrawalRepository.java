package com.farzam.custody.withdrawal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
