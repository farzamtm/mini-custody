package com.farzam.custody.chain;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which chain custody-api watches, and how.
 *
 * <p>Reading only. This service has no key and no way to send a transaction — the signer does that
 * — so everything here is about finding out what the chain says happened.
 *
 * @param rpcUrl the JSON-RPC endpoint; Anvil locally
 * @param chainId the network. Not used for anything yet on this side: custody-api signs nothing, so
 *     it has no transaction to put a chain id into. Kept because a watcher pointed at the wrong
 *     network is a failure worth being able to detect, and the check wants somewhere to read the
 *     expected value from.
 * @param confirmations how many blocks must sit on top of a receipt before the ledger settles
 *     against it. Three, which is a number chosen to keep a local demo quick rather than because it
 *     means anything: a real deployment would use Ethereum's {@code finalized} block tag, which is
 *     an actual guarantee rather than a guess about how deep a reorg can go.
 * @param hotWalletAddress the address the signer sends from. Public information — it is on every
 *     transaction — and it is here so reconciliation can ask the chain what that account holds.
 * @param pollInterval how often the confirmation watcher looks. A latency budget: it decides how
 *     long after the third confirmation a client sees {@code CONFIRMED}.
 * @param batchSize how many broadcast withdrawals one tick claims. The rows are locked while their
 *     receipts are fetched, so this trades latency against lock time, as the outbox relay's batch
 *     size does.
 * @param rpcTimeout how long to wait for the node. Short, because the watcher holds a database
 *     transaction open while it asks.
 * @param stuckAfter how long a withdrawal may sit still before reconciliation mentions it. It
 *     governs two stalls. In {@code BROADCAST} with no receipt it is not a failure — the signer's
 *     retry job resends, and a busy chain is slow — but an unmined transaction that is hours old is
 *     something an operator should see. In {@code APPROVED} it is more serious, because nothing
 *     retries at all: the outbox may never have published the event, or the signer may have
 *     dead-lettered it, and either way the client's funds stay held. One budget for both rather than
 *     two, even though signing normally takes about a second and mining takes as long as it takes.
 *     Generous in the approved direction is the right way to be wrong for a report a person reads.
 */
@ConfigurationProperties("chain")
public record ChainProperties(URI rpcUrl, long chainId, int confirmations, String hotWalletAddress,
        Duration pollInterval, int batchSize, Duration rpcTimeout, Duration stuckAfter) {

    /**
     * Defaults for everything that is not a deployment decision.
     *
     * <p>{@code confirmations} defaults to three rather than zero, because zero would mean settling
     * the ledger against a transaction in the block currently being built — the one most likely to
     * be reorganised away. An absent setting should not be the least safe setting.
     */
    public ChainProperties {
        confirmations = confirmations <= 0 ? 3 : confirmations;
        pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        rpcTimeout = rpcTimeout == null ? Duration.ofSeconds(5) : rpcTimeout;
        stuckAfter = stuckAfter == null ? Duration.ofMinutes(10) : stuckAfter;
        hotWalletAddress = hotWalletAddress == null ? "" : hotWalletAddress;
    }
}
