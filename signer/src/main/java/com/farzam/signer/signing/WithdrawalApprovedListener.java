package com.farzam.signer.signing;

import com.farzam.events.EventEnvelope;
import com.farzam.events.EventFormatException;
import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalApproved;
import com.farzam.events.WithdrawalBroadcast;
import com.farzam.events.WithdrawalSigningFailed;
import com.farzam.signer.chain.EthereumRpc;
import com.farzam.signer.crypto.HotWallet;
import com.farzam.signer.messaging.ProcessedEvents;
import com.farzam.signer.outbox.OutboxWriter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The signer's one way in.
 *
 * <p>Everything one message causes happens in a single database transaction: the event is recorded
 * as seen, the nonce is reserved, the signature is written and the result is queued for publication.
 * Either all of that is committed or none of it is, which is what makes each of the failures
 * survivable. A crash halfway leaves no spent nonce and no orphan signature; a redelivery finds the
 * event already in {@code processed_events} and does nothing.
 *
 * <p>What is deliberately <em>outside</em> the transaction is the broadcast. Sending a payment and
 * then failing to commit would put money on the chain with no record here that it ever happened, so
 * the order is: commit first, send afterwards, and let the retry job resend anything that did not
 * make it. {@link TransactionSigned} is how "afterwards" is arranged.
 *
 * <p><b>A refusal is committed, not thrown.</b> This is the design decision most worth
 * understanding here. Letting {@link SigningRefusedException} escape would roll the transaction
 * back, retry the message three times, and dead-letter it — leaving the withdrawal APPROVED in
 * custody-api, the client's funds held, and the explanation in a topic nobody reads. Instead the
 * refusal becomes a {@code WithdrawalSigningFailed} event in the same transaction, so custody-api
 * can put the money back. The signer saying no out loud is what makes the hold releasable.
 */
/*
 * The suppression is class-wide rather than per line, because the justification is a property of
 * the whole class and is easier to review stated once: every value this class logs is a UUID or an
 * enum constant, neither of which can carry a newline and forge a log line. The single free-text
 * field it handles, the refusal reason, is built by SigningPolicy from string literals and numbers
 * and never from anything that arrived on the topic.
 */
@SuppressFBWarnings(value = "CRLF_INJECTION_LOGS", justification = "See the note above the class.")
@Component
public class WithdrawalApprovedListener {

    /**
     * One name for two things that must agree: the Kafka consumer group, and the {@code consumer}
     * column in {@code processed_events}. Drifting apart would fail silently — the group would keep
     * its offsets under one name while duplicates were checked under another.
     */
    public static final String CONSUMER = "signer";

    private static final Logger LOG = LoggerFactory.getLogger(WithdrawalApprovedListener.class);

    private final ProcessedEvents processedEvents;
    private final SigningPolicy policy;
    private final SigningLog signingLog;
    private final Nonces nonces;
    private final TransactionSigner signer;
    private final EthereumRpc chain;
    private final OutboxWriter outbox;
    private final ApplicationEventPublisher events;
    private final String hotWalletAddress;

    @SuppressWarnings("checkstyle:ParameterNumber")
    WithdrawalApprovedListener(ProcessedEvents processedEvents, SigningPolicy policy, SigningLog signingLog,
            Nonces nonces, TransactionSigner signer, EthereumRpc chain, OutboxWriter outbox,
            ApplicationEventPublisher events, HotWallet hotWallet) {
        this.processedEvents = processedEvents;
        this.policy = policy;
        this.signingLog = signingLog;
        this.nonces = nonces;
        this.signer = signer;
        this.chain = chain;
        this.outbox = outbox;
        this.events = events;
        this.hotWalletAddress = hotWallet.requireAddress();
    }

    /**
     * Handles one approval.
     *
     * <p>The duplicate check comes before the payload is parsed: it needs only the envelope, and an
     * event already acted on should cost one indexed insert that does nothing rather than a policy
     * evaluation and a round trip to the chain.
     *
     * @param message the raw JSON envelope
     * @throws EventFormatException if the message is not an approval this service can read;
     *     registered as non-retryable, so it is dead-lettered immediately rather than parsed three
     *     more times
     */
    @KafkaListener(topics = Topics.WITHDRAWALS, groupId = CONSUMER)
    @Transactional
    public void onWithdrawalApproved(String message) {
        EventEnvelope event = EventJson.read(message, EventEnvelope.class);

        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            LOG.debug("skipping event {}, already handled", event.eventId());
            return;
        }

        // A switch expression with no default: it must be exhaustive, so a fourth event type makes
        // this file stop compiling rather than being silently ignored.
        WithdrawalApproved approved = switch (event.eventType()) {
            case WITHDRAWAL_APPROVED -> event.payloadAs(WithdrawalApproved.class);
            // Results are what this service publishes, not what it consumes. One here means somebody
            // is producing to the wrong topic, and the dead-letter topic is where that becomes
            // visible instead of being dropped.
            case WITHDRAWAL_BROADCAST, WITHDRAWAL_SIGNING_FAILED -> throw new EventFormatException(
                    event.eventType().wireName() + " does not belong on " + Topics.WITHDRAWALS);
        };

        try {
            signAndQueue(approved);
        } catch (SigningRefusedException refusal) {
            refuse(approved.withdrawalId(), refusal.getMessage());
        }
    }

    /**
     * The happy path, up to but not including the network.
     *
     * <p>The order of the two guards matters. Policy runs first, so an event that should never have
     * been sent is refused without reserving a nonce or reading the chain. The signing log is
     * checked second, because "this withdrawal already has a signature" is a different question from
     * "this event was already handled" — the first catches a second, differently-identified event
     * about the same withdrawal, which is what a replayed topic or a misconfigured custody-api
     * produces.
     */
    private void signAndQueue(WithdrawalApproved approved) {
        policy.check(approved);

        UUID withdrawalId = approved.withdrawalId();
        Optional<SignedTransaction> existing = signingLog.find(withdrawalId);
        if (existing.isPresent()) {
            // Not an error and not a no-op. The signature stands — re-signing with a fresh nonce is
            // the one thing that could pay twice — but the result is republished, because an event
            // arriving about a withdrawal custody-api has apparently not caught up with is exactly
            // when it needs telling again. Its consumer is idempotent, so a repeat costs nothing.
            LOG.info(
                    "withdrawal {} is already signed; republishing the result rather than signing again",
                    withdrawalId);
            publishBroadcast(existing.get());
            return;
        }

        long nonce = nonces.reserve(hotWalletAddress);
        SignedTransaction signed = signer
                .sign(withdrawalId, approved.destination(), approved.amountWei(), nonce, chain.currentFees());
        signingLog.record(signed);
        publishBroadcast(signed);
    }

    /**
     * Queues the result and arranges for the bytes to be sent once this transaction commits.
     */
    private void publishBroadcast(SignedTransaction signed) {
        outbox.append(
                Topics.SIGNER_RESULTS,
                EventType.WITHDRAWAL_BROADCAST,
                signed.withdrawalId(),
                new WithdrawalBroadcast(signed.withdrawalId(), signed.txHash()));
        events.publishEvent(new TransactionSigned(signed));
    }

    /**
     * Says no, in a way that reaches the client.
     */
    private void refuse(UUID withdrawalId, String reason) {
        LOG.info("refusing to sign withdrawal {}: {}", withdrawalId, reason);
        outbox.append(
                Topics.SIGNER_RESULTS,
                EventType.WITHDRAWAL_SIGNING_FAILED,
                withdrawalId,
                new WithdrawalSigningFailed(withdrawalId, reason));
    }
}
