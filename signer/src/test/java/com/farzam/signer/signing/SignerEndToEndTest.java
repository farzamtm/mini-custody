package com.farzam.signer.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.farzam.events.ApprovalStatement;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventSignature;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalBroadcast;
import com.farzam.events.WithdrawalSigningFailed;
import com.farzam.signer.support.AbstractSignerTest;
import com.farzam.signer.support.CapturedLogs;
import com.farzam.signer.support.TestRpc;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M5's four acceptance criteria, against a real chain.
 *
 * <p>Everything here goes in through Kafka and comes out on Anvil, because the milestone's claim is
 * about the whole path: an approval arrives, a policy accepts it, a key is unsealed, a transaction is
 * signed, broadcast and mined, and a result is published. Testing the pieces separately — and the
 * unit tests beside this one do — would not have caught a wrong chain id, a wrong nonce, or an RLP
 * encoding the EVM rejects, each of which produces perfectly valid-looking bytes that no amount of
 * mocking would question.
 */
@SpringBootTest
class SignerEndToEndTest extends AbstractSignerTest {

    /** 0.4 ETH: under the four-eyes threshold, so one approval is a quorum. */
    private static final BigInteger SMALL = new BigInteger("400000000000000000");

    private static final Duration WINDOW = Duration.ofSeconds(8);

    @Autowired
    private SigningLog signingLog;

    @Test
    void anApprovedWithdrawalIsSignedBroadcastAndMinedWithTheFundsArriving() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();
        TestRpc chain = chain();
        assertThat(chain.balanceOf(destination)).isZero();

        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        SMALL,
                        List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL))));

        // The destination's balance is the assertion that cannot be satisfied by anything except a
        // transaction the EVM accepted, executed and mined.
        await().atMost(WINDOW).untilAsserted(() -> assertThat(chain.balanceOf(destination)).isEqualTo(SMALL));

        SignedTransaction signed = signingLog.find(withdrawalId).orElseThrow();
        assertThat(signed.rawTransaction()).startsWith("0x");
        assertThat(chain.minedSuccessfully(signed.txHash())).isTrue();

        // And custody-api is told, exactly once, with the hash it will follow the payment by.
        List<ConsumerRecord<String, String>> results = drain(Topics.SIGNER_RESULTS, withdrawalId, WINDOW);
        assertThat(results).hasSize(1);
        EventEnvelope published = EventJson.read(results.getFirst().value(), EventEnvelope.class);
        assertThat(published.eventType()).isEqualTo(EventType.WITHDRAWAL_BROADCAST);
        assertThat(published.payloadAs(WithdrawalBroadcast.class).txHash()).isEqualTo(signed.txHash());
    }

    /**
     * The producing half of M7: what the relay puts on the topic is something custody-api can prove
     * came from here.
     *
     * <p>Verified with {@link com.farzam.events.EventSignature} against the public key matching the
     * configured seed — the same call custody-api makes — rather than by asserting the header is
     * merely present. A header containing the right number of arbitrary bytes would satisfy a
     * presence check and would be rejected by the real consumer, which is a test that passes while
     * the system is broken.
     */
    @Test
    void everyResultCarriesASignatureCustodyApiCanVerify() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();

        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        SMALL,
                        List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL))));

        List<ConsumerRecord<String, String>> results = drain(Topics.SIGNER_RESULTS, withdrawalId, WINDOW);
        assertThat(results).hasSize(1);

        ConsumerRecord<String, String> result = results.getFirst();
        Header header = result.headers().lastHeader(EventSignature.HEADER);

        assertThat(header).as("the relay attached a signature").isNotNull();
        assertThat(
                EventSignature
                        .verify(RESULTS_PUBLIC_KEY, result.value(), new String(header.value(), StandardCharsets.UTF_8)))
                .as("and it verifies against the signer's public key")
                .isTrue();
    }

    @Test
    void theSameApprovalDeliveredTwiceSignsOnce() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();
        UUID eventId = UUID.randomUUID();
        String event = approvalEvent(
                eventId,
                withdrawalId,
                destination,
                SMALL,
                List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL)));

        publish(Topics.WITHDRAWALS, withdrawalId, event);
        publish(Topics.WITHDRAWALS, withdrawalId, event);

        TestRpc chain = chain();
        await().atMost(WINDOW).untilAsserted(() -> assertThat(chain.balanceOf(destination)).isEqualTo(SMALL));

        // The balance settling at exactly one transfer is the property. A second signature would use
        // a second nonce, be a genuinely different transaction, and be mined alongside the first —
        // so a duplicate here is not a duplicate message, it is a duplicate payment.
        await().during(Duration.ofSeconds(2))
                .atMost(WINDOW)
                .untilAsserted(() -> assertThat(chain.balanceOf(destination)).isEqualTo(SMALL));
        assertThat(drain(Topics.SIGNER_RESULTS, withdrawalId, WINDOW)).hasSize(1);
    }

    @Test
    void aSecondEventAboutAnAlreadySignedWithdrawalRepublishesRatherThanResigning() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();

        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        SMALL,
                        List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL))));

        TestRpc chain = chain();
        await().atMost(WINDOW).untilAsserted(() -> assertThat(chain.balanceOf(destination)).isEqualTo(SMALL));
        long nonce = signingLog.find(withdrawalId).orElseThrow().nonce();

        // A different event id, so the duplicate check does not catch it — which is what a replayed
        // topic or a second custody-api deployment would produce.
        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        SMALL,
                        List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL))));

        await().atMost(WINDOW)
                .untilAsserted(
                        () -> assertThat(drain(Topics.SIGNER_RESULTS, withdrawalId, Duration.ofSeconds(2))).hasSize(2));

        // Told twice, paid once, and the nonce is the evidence: the same one, so the same
        // transaction, so the chain had nothing new to mine.
        assertThat(signingLog.find(withdrawalId).orElseThrow().nonce()).isEqualTo(nonce);
        assertThat(chain.balanceOf(destination)).isEqualTo(SMALL);
    }

    @Test
    void anEventWithAForgedApprovalSignatureIsRefused() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();

        // The realistic forgery: a genuine signature by a genuinely trusted approver, over a
        // statement about a much smaller amount, replayed onto an event asking for ten times as much.
        // Everything about the approval is authentic except what it is attached to.
        ApprovalStatement whatWasReallySigned = new ApprovalStatement(withdrawalId, destination, BigInteger.ONE);
        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(withdrawalId, destination, SMALL, List.of(APPROVER_ONE.approve(whatWasReallySigned))));

        WithdrawalSigningFailed refusal = awaitRefusal(withdrawalId);
        assertThat(refusal.reason()).contains("valid approvals");

        assertThat(signingLog.find(withdrawalId)).isEmpty();
        assertThat(chain().balanceOf(destination)).isZero();
    }

    @Test
    void anApprovalFromSomebodyTheSignerDoesNotTrustIsWorthNothing() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();

        // A flawless signature over exactly the right statement, from a key this service has never
        // been told about. It verifies as arithmetic and counts for nothing, which is the difference
        // between checking a signature and checking an approval.
        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        SMALL,
                        List.of(OUTSIDER.approve(withdrawalId, destination, SMALL))));

        assertThat(awaitRefusal(withdrawalId).reason()).contains("valid approvals");
        assertThat(signingLog.find(withdrawalId)).isEmpty();
        assertThat(chain().balanceOf(destination)).isZero();
    }

    @Test
    void anAmountAboveTheHotWalletCapIsRefusedHoweverWellApproved() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();
        BigInteger overCap = new BigInteger("6000000000000000000"); // 6 ETH against a 5 ETH cap

        publish(
                Topics.WITHDRAWALS,
                withdrawalId,
                approvalEvent(
                        withdrawalId,
                        destination,
                        overCap,
                        List.of(
                                APPROVER_ONE.approve(withdrawalId, destination, overCap),
                                APPROVER_TWO.approve(withdrawalId, destination, overCap))));

        assertThat(awaitRefusal(withdrawalId).reason()).contains("per-transaction limit");
        assertThat(signingLog.find(withdrawalId)).isEmpty();
        assertThat(chain().balanceOf(destination)).isZero();
    }

    /**
     * M5's fourth criterion, and the one that is a property of every line of the service rather than
     * of any one of them.
     *
     * <p>A successful signing is driven with the root logger at DEBUG and every line captured, then
     * the two secrets are looked for in the result. DEBUG rather than the default, because the
     * question is not whether the happy path is quiet — it is whether a diagnostic somebody added at
     * three in the morning would be.
     */
    @Test
    void neitherThePrivateKeyNorTheMasterKeyReachesTheLogs() {
        UUID withdrawalId = UUID.randomUUID();
        String destination = freshAddress();

        CapturedLogs.Captured logged = CapturedLogs.whileRunning(() -> {
            publish(
                    Topics.WITHDRAWALS,
                    withdrawalId,
                    approvalEvent(
                            withdrawalId,
                            destination,
                            SMALL,
                            List.of(APPROVER_ONE.approve(withdrawalId, destination, SMALL))));
            TestRpc chain = chain();
            await().atMost(WINDOW).untilAsserted(() -> assertThat(chain.balanceOf(destination)).isEqualTo(SMALL));
        });

        // A whole signing really was captured, or the rest of this proves nothing: an assertion that
        // a secret is absent from an empty string passes every time.
        assertThat(logged.from("com.farzam")).contains(withdrawalId.toString());

        String everything = logged.everything();
        assertThat(everything).doesNotContain(HOT_WALLET_PRIVATE_KEY);
        assertThat(everything).doesNotContain(HOT_WALLET_PRIVATE_KEY.toUpperCase(Locale.ROOT));
        assertThat(everything).doesNotContain(MASTER_KEY);

        // The signed transaction is not a secret — it is about to be public on a chain — and Spring's
        // RestClient logs every request body at DEBUG, so it is in the framework's output no matter
        // what this service does. What is asserted is narrower and is the part this repository
        // controls: none of its own loggers emit it. That keeps the framework's wire logging a known
        // quantity rather than a place things can quietly start appearing.
        assertThat(logged.from("com.farzam"))
                .doesNotContain(signingLog.find(withdrawalId).orElseThrow().rawTransaction());
    }

    private WithdrawalSigningFailed awaitRefusal(UUID withdrawalId) {
        Optional<WithdrawalSigningFailed> refusal = drain(Topics.SIGNER_RESULTS, withdrawalId, WINDOW).stream()
                .map(record -> EventJson.read(record.value(), EventEnvelope.class))
                .filter(event -> event.eventType() == EventType.WITHDRAWAL_SIGNING_FAILED)
                .map(event -> event.payloadAs(WithdrawalSigningFailed.class))
                .findFirst();
        assertThat(refusal).as("a refusal on %s", Topics.SIGNER_RESULTS).isPresent();
        return refusal.orElseThrow();
    }
}
