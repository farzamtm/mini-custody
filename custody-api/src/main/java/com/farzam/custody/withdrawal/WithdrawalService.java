package com.farzam.custody.withdrawal;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The withdrawal use cases: request one, look one up.
 *
 * <p>Thin on purpose. The work of requesting a withdrawal is in {@link WithdrawalWriter}, inside a
 * transaction; what lives here is the one thing that cannot live inside it, which is recovering from
 * that transaction failing.
 */
@Service
public class WithdrawalService {

    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalService.class);

    private final WithdrawalWriter writer;
    private final WithdrawalRepository withdrawals;

    WithdrawalService(WithdrawalWriter writer, WithdrawalRepository withdrawals) {
        this.writer = writer;
        this.withdrawals = withdrawals;
    }

    /**
     * Requests a withdrawal, idempotently on the client's {@code Idempotency-Key}.
     *
     * <p><b>The race this catches.</b> A client's HTTP call times out and it retries, so two
     * identical requests are in flight at once. Both run {@code findByClientIdAndIdempotencyKey},
     * both find nothing, and both go on to insert — the classic check-then-act gap. One insert wins
     * the {@code unique (client_id, idempotency_key)} index and the other is rejected by Postgres.
     * Letting the index arbitrate rather than the application is what closes the gap: there is no
     * window between the check and the write for the second request to fit through, because the check
     * <em>is</em> the write.
     *
     * <p><b>Why the recovery is a second call rather than a re-read.</b> A constraint violation
     * poisons the whole Postgres transaction — every subsequent statement in it fails with
     * "current transaction is aborted" — so the loser cannot simply look the winner's row up where it
     * stands. It needs a new transaction, and the only way to get one through a proxy is to call the
     * bean again from outside. The second attempt finds the committed row and takes the replay path.
     *
     * <p>Exactly one retry, not a loop. The second attempt cannot lose the same race: the row it
     * collided with is committed, so the look-up now finds it. A violation on the second attempt is
     * therefore something else entirely — a foreign key, a check constraint — and rethrowing is the
     * honest answer.
     *
     * @param command the request, in domain types
     * @return the withdrawal, in {@code PENDING_APPROVAL}, with its funds held
     * @throws IdempotencyKeyReusedException if the key was used before for a different request
     */
    public Withdrawal request(WithdrawalCommand command) {
        try {
            return writer.record(command);
        } catch (DataIntegrityViolationException race) {
            LOG.debug("lost the insert race on an idempotency key; resolving against the committed row", race);
            return writer.record(command);
        }
    }

    /**
     * Looks a withdrawal up.
     *
     * <p>Returns an {@link Optional} rather than throwing, and leaves turning absence into a
     * {@code 404} to the controller. Whether a missing row is an error depends on who is asking,
     * and that is not a decision this method has the context to make.
     *
     * @param id the withdrawal id
     * @return the withdrawal, if it exists
     */
    public Optional<Withdrawal> find(UUID id) {
        return withdrawals.findById(id);
    }
}
