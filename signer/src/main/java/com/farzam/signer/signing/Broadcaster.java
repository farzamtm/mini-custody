package com.farzam.signer.signing;

import com.farzam.signer.chain.EthereumRpc;
import com.farzam.signer.chain.RpcException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts signed transactions on the network, and keeps trying.
 *
 * <p><b>Resending is always safe; re-signing never is.</b> A transaction's hash is a hash of its
 * bytes, so sending identical bytes twice is one transaction as far as the network is concerned —
 * the second node to see it says it already knows and nothing else happens. Signing the same
 * withdrawal again would allocate a new nonce and produce a second, genuinely different transaction,
 * and if the first was merely slow rather than lost, both can be mined and the client is paid twice.
 * Everything in this class follows from that asymmetry: {@code signing_log} keeps the raw bytes so
 * that recovery means resend.
 *
 * <p><b>A failed broadcast is not an error.</b> The signature is committed and the raw transaction
 * is on disk, so an unreachable node is a delay. The retry job below picks the row up on its next
 * pass and sends exactly the same bytes.
 *
 * <p><b>What this deliberately does not do is decide that a transaction is stuck.</b> A transaction
 * whose fee was too low sits in the mempool indefinitely, and the fix is to replace it with one
 * carrying the <em>same nonce</em> and a fee at least about 10% higher, so that at most one of the
 * two can ever be mined. That is a signing decision with real money attached, it needs a view of how
 * long is too long, and it belongs with the confirmation watcher that is actually watching — M6. Here
 * a transaction is resent, never repriced.
 */
@Component
public class Broadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(Broadcaster.class);

    private static final int BATCH = 50;

    private final EthereumRpc chain;
    private final SigningLog signingLog;
    private final Duration retryAfter;

    Broadcaster(EthereumRpc chain, SigningLog signingLog,
            @Value("${signer.broadcast.retry-after:10s}") Duration retryAfter) {
        this.chain = chain;
        this.signingLog = signingLog;
        this.retryAfter = retryAfter;
    }

    /**
     * Sends a transaction the moment its signature is durable.
     *
     * <p>{@link TransactionPhase#AFTER_COMMIT} is the whole reason this is an event listener rather
     * than the last line of the signing method. Sending inside the transaction risks paying somebody
     * and then rolling back the only record that it happened; sending after the commit risks a
     * delay, which the retry job absorbs. Of the two orderings only one can lose money.
     *
     * @param event the transaction that has just been committed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionSigned(TransactionSigned event) {
        broadcast(event.signed());
    }

    /**
     * Resends everything that was signed and is not known to have reached the network.
     *
     * <p>Lowest nonce first, because a gap blocks everything behind it: nonce 8 cannot be mined
     * while 7 is missing, so there is no point spending the batch on 8.
     *
     * @return how many were successfully sent
     */
    public int resendBacklog() {
        List<SignedTransaction> backlog = signingLog.unbroadcast(retryAfter, BATCH);
        int sent = 0;
        for (SignedTransaction signed : backlog) {
            if (broadcast(signed)) {
                sent++;
            } else {
                // Stop at the first failure. The usual cause is that the node is unreachable, in
                // which case the rest of the batch was not going anywhere either, and the ordering
                // above only means anything if it is respected on the way out as well.
                break;
            }
        }
        if (sent > 0) {
            LOG.info("resent {} of {} transactions that had not reached the network", sent, backlog.size());
        }
        return sent;
    }

    /**
     * One send, with the failure treated as a delay.
     *
     * @param signed what to send
     * @return whether the network has it now
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "A transaction hash, which this service computed as hex from keccak-256 "
                    + "of bytes it signed. It cannot contain a newline.")
    private boolean broadcast(SignedTransaction signed) {
        try {
            chain.sendRawTransaction(signed.rawTransaction(), signed.txHash());
        } catch (RpcException failure) {
            if (alreadyMined(signed)) {
                LOG.info("{} was already mined; the resend was redundant", signed.txHash());
            } else {
                // Warn rather than error: nothing is lost, the row keeps its null broadcast_at, and
                // the retry job will be back. It becomes somebody's problem through the age of the
                // oldest unbroadcast row, which is the metric a production deployment would alert on.
                LOG.warn("could not broadcast {}; it stays queued for retry", signed.txHash(), failure);
                return false;
            }
        }
        signingLog.markBroadcast(signed.withdrawalId());
        LOG.info("broadcast {} for withdrawal {}", signed.txHash(), signed.withdrawalId());
        return true;
    }

    /**
     * Was that refusal about a transaction the chain already has?
     *
     * <p>The case this exists for is narrow and entirely real: the listener sends successfully and
     * dies before stamping {@code broadcast_at}, the transaction is mined, and the retry job comes
     * back to a node that now answers "nonce too low" — because the nonce has indeed been used, by
     * this very transaction. Without this check that row stays in the backlog for ever, resent every
     * five seconds, and eventually trips the alarm on the age of the oldest unbroadcast row for a
     * payment that went through perfectly.
     *
     * <p>The check is a receipt lookup rather than a second look at the error text, because the text
     * cannot distinguish that from the dangerous version of the same message — a nonce spent by some
     * other transaction, which leaves this one permanently unmineable and genuinely does need
     * somebody. A receipt for <em>this hash</em> is the only evidence that settles it.
     *
     * <p>An unreachable node lands here too and answers false, which is the right answer: nothing is
     * known, so nothing is assumed.
     */
    private boolean alreadyMined(SignedTransaction signed) {
        try {
            return chain.hasReceipt(signed.txHash());
        } catch (RpcException unknown) {
            return false;
        }
    }
}
