package com.farzam.custody.withdrawal;

import com.farzam.custody.chain.EthereumAddress;
import com.farzam.custody.ledger.Account;
import com.farzam.custody.ledger.AccountRepository;
import com.farzam.custody.ledger.AccountType;
import com.farzam.custody.ledger.Entry;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.ledger.UnknownAccountException;
import com.farzam.custody.whitelist.AddressNotWhitelistedException;
import com.farzam.custody.whitelist.WhitelistService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One withdrawal request, one database transaction.
 *
 * <p>A separate bean from {@link WithdrawalService} for the same reason {@code JournalWriter} is
 * separate from {@code LedgerService}: {@code @Transactional} is implemented with a proxy, so a call
 * from inside the same object never reaches it, and the caller here specifically needs to react to a
 * transaction that has <em>failed</em> — which it can only do from outside.
 *
 * <p>Everything below commits together or not at all. The withdrawal row and the ledger hold are one
 * atomic fact: a withdrawal that exists without its funds held would let the same balance be spent
 * again, and a hold without a withdrawal would strand the money.
 */
@Component
class WithdrawalWriter {

    private final WithdrawalRepository withdrawals;
    private final AccountRepository accounts;
    private final WhitelistService whitelist;
    private final LedgerService ledger;

    WithdrawalWriter(WithdrawalRepository withdrawals, AccountRepository accounts, WhitelistService whitelist,
            LedgerService ledger) {
        this.withdrawals = withdrawals;
        this.accounts = accounts;
        this.whitelist = whitelist;
        this.ledger = ledger;
    }

    /**
     * Creates a withdrawal and holds the funds, or recognises a request already made.
     *
     * <p>The order of the checks is not arbitrary. Identity first (which account, whose is it), then
     * the idempotency look-up, then policy, then money. Checking idempotency before the whitelist
     * means a retry of an accepted request keeps working even if the address was removed from the
     * whitelist in the meantime — the decision was made when the request was accepted, and a retry is
     * not a new decision.
     *
     * @param command the request, in domain types
     * @return the withdrawal — a fresh one, or the one this key already created
     * @throws UnknownAccountException if there is no such account
     * @throws NotAClientAccountException if the account is a system account
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     * @throws AddressNotWhitelistedException if the client has not declared this destination
     * @throws com.farzam.custody.ledger.InsufficientFundsException if the balance will not cover it
     */
    @Transactional
    public Withdrawal record(WithdrawalCommand command) {
        Account account = accounts.findById(command.accountId())
                .orElseThrow(() -> new UnknownAccountException(command.accountId()));
        if (account.getType() != AccountType.CLIENT) {
            throw new NotAClientAccountException(account.getId(), account.getType());
        }
        UUID clientId = account.getClientId();
        String destination = EthereumAddress.normalise(command.destination());
        String requestHash = RequestHash.of(account.getId(), destination, command.amountWei());

        Optional<Withdrawal> alreadyRequested = withdrawals
                .findByClientIdAndIdempotencyKey(clientId, command.idempotencyKey());
        if (alreadyRequested.isPresent()) {
            return replayOf(alreadyRequested.get(), requestHash);
        }

        if (!whitelist.isAllowed(clientId, destination)) {
            throw new AddressNotWhitelistedException(clientId, destination);
        }

        Withdrawal withdrawal = Withdrawal.requested(
                clientId,
                account.getId(),
                destination,
                command.amountWei(),
                command.idempotencyKey(),
                requestHash);

        // saveAndFlush, not save. JPA would otherwise hold the INSERT until the transaction commits,
        // which is after this method returns — and a unique-key collision raised at commit time is an
        // exception nobody in this call stack can still do anything about. Flushing here turns the
        // race into a DataIntegrityViolationException that WithdrawalService can catch and resolve.
        withdrawals.saveAndFlush(withdrawal);

        hold(withdrawal);
        return withdrawal;
    }

    /**
     * Decides what a second request under an existing key means.
     *
     * <p>Comparing hashes rather than trusting the key is the difference between idempotency and a
     * hole: the key says "this is the same request", and the hash is what checks whether that claim
     * is true.
     */
    private Withdrawal replayOf(Withdrawal original, String requestHash) {
        if (!RequestHash.matches(original.getRequestHash(), requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        // The same request as before. Return the original and, crucially, do not hold again: the
        // funds for this withdrawal are already sitting in PENDING_OUT.
        return original;
    }

    /**
     * Moves the amount out of the client's balance and into {@code PENDING_OUT}.
     *
     * <p>At request time, not at send time. Otherwise two requests arriving together would both see
     * the full balance and both be accepted, and the second one would only fail minutes later when
     * the signer tried to spend money that was no longer there. Holding here makes the second request
     * fail immediately, with nothing signed and nothing to unwind.
     *
     * <p>This runs inside the caller's transaction, so under {@code ledger.locking=optimistic} the
     * retry loop inside {@link LedgerService} cannot help — a conflict has already rolled this
     * transaction back. The request then fails, the client retries, and the retry is safe precisely
     * because it carries the same idempotency key. Under the default pessimistic strategy, which
     * ADR 0001 explains, a contending writer waits instead of failing and the question does not
     * arise.
     */
    private void hold(Withdrawal withdrawal) {
        ledger.post(
                JournalKind.WITHDRAWAL_HOLD,
                withdrawal.getId(),
                List.of(
                        new Entry(withdrawal.getAccountId(), withdrawal.getAmount().negate()),
                        new Entry(SystemAccounts.PENDING_OUT, withdrawal.getAmount())));
    }
}
