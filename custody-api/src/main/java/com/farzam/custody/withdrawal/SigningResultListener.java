package com.farzam.custody.withdrawal;

import com.farzam.custody.ledger.Entry;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.messaging.ProcessedEvents;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventFormatException;
import com.farzam.events.EventJson;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalBroadcast;
import com.farzam.events.WithdrawalSigningFailed;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies what the signer reports back: a transaction hash, or a refusal.
 *
 * <p>This is the idempotent-consumer pattern in full. Everything one message does — recording that
 * the message was seen, moving the withdrawal, releasing the hold — happens in one transaction, so
 * the four ways Kafka produces a duplicate (a producer retry, a relay resend, a rebalance, a crash
 * before the offset commit) all reduce to the same harmless outcome.
 *
 * <p>{@code @Transactional} on a {@code @KafkaListener} works because the container invokes this
 * bean through its proxy, from outside — unlike a self-call, which is the trap {@code JournalWriter}
 * documents. What the transaction does <em>not</em> cover is the Kafka offset: that is committed
 * after the listener returns, by the container, and there is no shared transaction between Postgres
 * and the broker. A crash in between redelivers the message, which is exactly the case
 * {@code processed_events} handles.
 */
@Component
public class SigningResultListener {

    /**
     * One name for two things that have to agree: the Kafka consumer group and the {@code consumer}
     * column in {@code processed_events}.
     *
     * <p>If they drifted apart nothing would fail. The group would keep its offsets under one name
     * while duplicates were checked under another, and the mismatch would only show up as work done
     * twice, months later, in a way nobody would trace back to a string literal.
     */
    public static final String CONSUMER = "custody-api";

    private static final Logger LOG = LoggerFactory.getLogger(SigningResultListener.class);

    private final ProcessedEvents processedEvents;
    private final WithdrawalRepository withdrawals;
    private final LedgerService ledger;

    SigningResultListener(ProcessedEvents processedEvents, WithdrawalRepository withdrawals, LedgerService ledger) {
        this.processedEvents = processedEvents;
        this.withdrawals = withdrawals;
        this.ledger = ledger;
    }

    /**
     * Handles one result from the signer.
     *
     * <p>The duplicate check comes first, before the payload is even parsed. It only needs the
     * envelope, and an event already applied should cost one indexed insert that does nothing rather
     * than a parse and a round trip to the withdrawal.
     *
     * <p>A message that fails here is retried by {@code DefaultErrorHandler} and then dead-lettered.
     * Because the failure rolls the transaction back, the {@code processed_events} row goes with it,
     * so a retry is a genuine retry rather than a replay that has already marked itself done.
     *
     * @param message the raw JSON envelope
     * @throws EventFormatException if the message is not an event this service can read — registered
     *     as non-retryable, so it is dead-lettered immediately
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "A UUID and an enum constant. The one field on these events that is "
                    + "free text, the signer's failure reason, is deliberately never logged.")
    @KafkaListener(topics = Topics.SIGNER_RESULTS, groupId = CONSUMER)
    @Transactional
    public void onSigningResult(String message) {
        EventEnvelope event = EventJson.read(message, EventEnvelope.class);

        if (!processedEvents.markProcessed(CONSUMER, event.eventId())) {
            LOG.debug("skipping event {}, already applied", event.eventId());
            return;
        }

        // A switch expression with no default, not a switch statement. The difference is not style:
        // an expression over an enum must be exhaustive, so a fourth event type makes this file stop
        // compiling and say so. A statement would have accepted the new constant silently and done
        // nothing with it, and the withdrawal would sit in APPROVED with nobody able to say why.
        Withdrawal affected = switch (event.eventType()) {
            case WITHDRAWAL_BROADCAST -> recordBroadcast(event.payloadAs(WithdrawalBroadcast.class));
            case WITHDRAWAL_SIGNING_FAILED -> releaseAfterFailure(event.payloadAs(WithdrawalSigningFailed.class));
            // An approval is something this service publishes, not something it consumes. Meeting
            // one here means somebody is producing to the wrong topic, and the dead-letter topic is
            // where that becomes visible instead of being silently ignored.
            case WITHDRAWAL_APPROVED ->
                throw new EventFormatException("a WithdrawalApproved does not belong on " + Topics.SIGNER_RESULTS);
        };

        // Enum and UUID only. The signer's `reason` is stored on the withdrawal but never logged: it
        // is text from across a service boundary, and a newline in it would let whoever produced it
        // forge log lines. The id is enough to find the row that has the words.
        LOG.info("applied {} to withdrawal {}", event.eventType(), affected.getId());
    }

    /**
     * The signer has a transaction on chain.
     *
     * <p>Nothing is posted to the ledger. Broadcast is not settled: the transaction is in the
     * mempool and can still be dropped, replaced or reverted, so the funds stay held in
     * {@code PENDING_OUT} where they have been since the request. M6's watcher settles them against
     * {@code EXTERNAL} once the receipt has three confirmations behind it.
     */
    private Withdrawal recordBroadcast(WithdrawalBroadcast broadcast) {
        Withdrawal withdrawal = require(broadcast.withdrawalId());
        withdrawal.broadcastAs(broadcast.txHash());
        return withdrawal;
    }

    /**
     * The signer refused. The money goes back.
     *
     * <p>Two idempotency guards cover this, deliberately. {@code processed_events} stops the same
     * event being applied twice, and the ledger's {@code unique (kind, reference_id)} stops
     * {@code WITHDRAWAL_RELEASE} being booked twice for this withdrawal however it is reached — by a
     * redelivery that slipped past the first guard, or by M6 releasing the same hold for a different
     * reason. Neither is redundant: the first protects the state machine, the second protects the
     * balance.
     */
    private Withdrawal releaseAfterFailure(WithdrawalSigningFailed failure) {
        Withdrawal withdrawal = require(failure.withdrawalId());
        withdrawal.endWith(WithdrawalStatus.FAILED, failure.reason());
        ledger.post(
                JournalKind.WITHDRAWAL_RELEASE,
                withdrawal.getId(),
                List.of(
                        new Entry(SystemAccounts.PENDING_OUT, withdrawal.getAmount().negate()),
                        new Entry(withdrawal.getAccountId(), withdrawal.getAmount())));
        return withdrawal;
    }

    /**
     * A result naming a withdrawal this service has never heard of.
     *
     * <p>custody-api is the only thing that creates withdrawals, so this cannot happen in a system
     * that is behaving. It is left retryable rather than dead-lettered on sight because the three
     * quick attempts cost nothing and the message ends up in the same place, and because the one
     * scenario that would produce it — a signer pointed at the wrong custody database — is worth
     * seeing three times in the logs.
     */
    private Withdrawal require(UUID withdrawalId) {
        return withdrawals.findById(withdrawalId)
                .orElseThrow(
                        () -> new IllegalStateException("a signer result names an unknown withdrawal " + withdrawalId));
    }
}
