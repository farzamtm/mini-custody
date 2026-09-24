package com.farzam.signer.signing;

import com.farzam.signer.chain.EthereumRpc;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out Ethereum transaction nonces, one at a time and never twice.
 *
 * <p><b>Which nonce this is.</b> Not the ECDSA signing nonce {@code k}, which is a secret number
 * inside a single signature and leaks the private key if it ever repeats — that one belongs to the
 * crypto library and RFC 6979 makes it deterministic. This is the public per-account counter that
 * orders an account's transactions: 0, 1, 2, and so on. Two names for two completely different
 * things, and the confusion between them has cost people real money.
 *
 * <p><b>What the counter buys.</b> A nonce can be mined at most once, so two transactions sharing
 * one are two candidates for a single slot and only one of them can ever be included. That makes the
 * nonce the last line of defence against a double payment: if every other guard in this service
 * failed and it signed the same withdrawal twice with the same nonce, the chain itself would still
 * only pay once. The corollary is the rule that shapes the retry design — a transaction that is
 * merely slow must be resent as the same bytes, never re-signed with a fresh nonce, because two
 * differently-nonced transactions to the same address can both be mined.
 *
 * <p><b>{@code FOR UPDATE}, inside the signing transaction.</b> Read-then-increment without the lock
 * is a check-then-act race: two withdrawals arriving together would both read 7, both sign with 7,
 * and one would be dropped by the network with no record here of which. The lock serialises the two,
 * and because it is taken in the same transaction that writes {@code signing_log} and the outbox
 * row, a rollback anywhere in that transaction gives the nonce back.
 *
 * <p>A gap is the cost of that. A transaction signed and then abandoned — the process dies after
 * commit and before broadcast, and every resend fails — leaves a nonce that nothing will ever use,
 * and nonce 8 cannot be mined until 7 is. The broadcast retry job exists to make that vanishingly
 * rare; fixing it when it happens means signing a zero-value transaction to yourself at the stuck
 * nonce, which is the standard remedy and is not automated here.
 */
@Component
public class Nonces {

    private static final String LOCK = """
            select next_nonce from chain_nonces where address = :address for update
            """;

    /**
     * {@code on conflict do nothing}, so two instances seeding the same wallet at the same moment
     * produce one row and no exception.
     */
    private static final String SEED = """
            insert into chain_nonces (address, next_nonce) values (:address, :nonce)
            on conflict (address) do nothing
            """;

    private static final String ADVANCE = """
            update chain_nonces set next_nonce = next_nonce + 1 where address = :address
            """;

    private final JdbcClient jdbc;
    private final EthereumRpc chain;

    Nonces(JdbcClient jdbc, EthereumRpc chain) {
        this.jdbc = jdbc;
        this.chain = chain;
    }

    /**
     * Takes the next nonce for an address and advances the counter.
     *
     * <p>{@link Propagation#MANDATORY} rather than {@code REQUIRED}. A nonce reserved in its own
     * transaction is a nonce that stays spent when the signing that needed it rolls back, and the
     * gap that leaves blocks every later transaction from the same wallet. Refusing to run outside a
     * transaction makes that mistake a startup-time failure rather than a stuck hot wallet.
     *
     * @param address the wallet, lower-case hex
     * @return the nonce to put in the transaction
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no
     *     transaction to join
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reserve(String address) {
        long nonce = lock(address).orElseGet(() -> seedFromChain(address));
        jdbc.sql(ADVANCE).param("address", address).update();
        return nonce;
    }

    /**
     * The first reservation for a wallet, which has to ask the chain where to start.
     *
     * <p>Only ever runs once per address, and it does a network call inside the signing transaction
     * to do it. That is a real cost paid exactly once, and the alternatives are worse: seeding at
     * startup makes the service refuse to boot when the node is briefly unavailable, and seeding
     * from zero would produce a stream of "nonce too low" refusals against any wallet that has ever
     * sent a transaction.
     *
     * <p>The re-read after the insert is not belt and braces. Another instance may have inserted the
     * row between the first {@code select} and this {@code insert}, in which case {@code on conflict
     * do nothing} did nothing and this one has to take the lock on the row that is now there — and
     * to read <em>its</em> value, which may already have been advanced.
     */
    private long seedFromChain(String address) {
        jdbc.sql(SEED)
                .param("address", address)
                .param("nonce", chain.transactionCount(address).longValueExact())
                .update();
        return lock(address).orElseThrow(
                () -> new IllegalStateException("the nonce row for " + address + " disappeared while seeding it"));
    }

    private Optional<Long> lock(String address) {
        return jdbc.sql(LOCK).param("address", address).query(Long.class).optional();
    }
}
