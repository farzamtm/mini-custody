package com.farzam.custody.ledger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The ledger's public face: post money movements, read balances.
 *
 * <p>Everything that moves money in this system goes through {@link #post}, and nothing else is
 * allowed to touch {@code accounts.balance}. That is the point of having a ledger at all — one
 * place where the double-entry rule, the locking and the idempotency all hold, instead of the same
 * three concerns half-implemented in every service that happens to move funds.
 *
 * <p>This bean is stateless, like every Spring bean must be: it is created once and called from
 * every request thread at the same time, so a field holding request data would be a concurrency bug
 * rather than a convenience. Everything a posting needs arrives as an argument.
 */
@Service
public class LedgerService {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerService.class);

    /** Never sleep longer than this between optimistic retries; a stuck request helps nobody. */
    private static final long MAX_BACKOFF_MILLIS = 8L;

    private final JournalWriter writer;
    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final int maxAttempts;

    LedgerService(JournalWriter writer, AccountRepository accounts, JournalEntryRepository entries,
            @Value("${ledger.max-attempts:50}") int maxAttempts) {
        this.writer = writer;
        this.accounts = accounts;
        this.entries = entries;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Books one business event as a journal transaction.
     *
     * <p>Idempotent on {@code (kind, referenceId)}: calling it twice for the same pair books once
     * and returns the same transaction id both times. Callers do not need to check first.
     *
     * <p>The retry loop lives out here, outside the transaction, because that is the only place it
     * can live. An optimistic conflict means the transaction has already rolled back; retrying
     * inside it would replay statements into a dead transaction. Under the default pessimistic
     * strategy nothing ever throws {@link OptimisticLockingFailureException}, so the loop runs
     * exactly once and costs nothing.
     *
     * @param kind what happened
     * @param referenceId what it happened to — the withdrawal id, the deposit id
     * @param entryList at least two entries summing to zero
     * @return the journal transaction id
     * @throws UnbalancedEntriesException if the entries do not sum to zero
     * @throws InsufficientFundsException if an account that may not go negative would
     * @throws UnknownAccountException if an entry names an account that does not exist
     * @throws LedgerContentionException if optimistic locking lost {@code ledger.max-attempts} times
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "A JournalKind, a UUID and an int. None can carry a newline, "
                    + "so no caller can forge a log line through them.")
    public UUID post(JournalKind kind, UUID referenceId, List<Entry> entryList) {
        Posting posting = new Posting(kind, referenceId, entryList);

        OptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return writer.write(posting);
            } catch (OptimisticLockingFailureException conflict) {
                lastConflict = conflict;
                LOG.debug("optimistic conflict booking {}/{}, attempt {}", kind, referenceId, attempt);
                backOff(attempt, kind, referenceId, lastConflict);
            }
        }
        throw new LedgerContentionException(kind, referenceId, maxAttempts, lastConflict);
    }

    /**
     * The cached balance, in wei.
     *
     * @param accountId the account
     * @return its {@code accounts.balance} column
     * @throws UnknownAccountException if there is no such account
     */
    public BigInteger balanceOf(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new UnknownAccountException(accountId)).getBalance();
    }

    /**
     * The balance recomputed from the journal, in wei.
     *
     * <p>{@code accounts.balance} is a cache of exactly this sum. Keeping a way to recompute it is
     * what makes the cache trustworthy: if the two ever disagree, the journal is right and something
     * has been writing to the balance column that should not have been.
     *
     * @param accountId the account
     * @return the sum of every journal entry pointing at it
     */
    public BigInteger recomputedBalanceOf(UUID accountId) {
        return entries.findByAccountId(accountId)
                .stream()
                .map(JournalEntry::getAmount)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /**
     * Linear backoff, capped.
     *
     * <p>Deliberately not random: jitter would need a PRNG, and a PRNG on a money path is a thing
     * reviewers and security scanners both have to stop and think about. Ten contending writers
     * spaced a millisecond apart is enough to break up the herd, because the winner of each round is
     * decided by the database, not by who wakes first.
     */
    private void backOff(int attempt, JournalKind kind, UUID referenceId, OptimisticLockingFailureException cause) {
        try {
            Thread.sleep(Math.min(attempt, MAX_BACKOFF_MILLIS));
        } catch (InterruptedException interrupted) {
            // Restore the flag we just cleared, so whoever asked us to stop is still heard.
            Thread.currentThread().interrupt();
            throw new LedgerContentionException(kind, referenceId, attempt, cause);
        }
    }
}
