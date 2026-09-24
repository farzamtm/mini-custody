package com.farzam.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The properties {@link EventJson} has to have for signatures to mean anything in M3 and M5.
 *
 * <p>These are not serialisation smoke tests. Each one pins down a way that two correct-looking JSON
 * encoders can disagree about the same data, and every disagreement turns a valid approval into an
 * invalid one at the moment the signer is deciding whether to touch a private key.
 *
 * <p>Several assertions are against a whole literal string rather than a field at a time. That is
 * deliberate: what has to be stable here is the exact byte sequence, so the test should fail if key
 * order, whitespace or number formatting changes — including in a Jackson upgrade, which is the
 * change most likely to do it quietly.
 */
class EventJsonTest {

    private static final UUID WITHDRAWAL = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final Instant NOON = Instant.parse("2026-09-24T18:00:00Z");

    @Test
    void aPayloadHasExactlyOneEncoding() {
        // The record declares withdrawalId, destination, amountWei, approvals — and none of that
        // order survives here. Jackson writes a record's components in constructor order by
        // default, so this test failing means SORT_CREATOR_PROPERTIES_FIRST has come back on and
        // every signature in the system is now over different bytes than before.
        assertThat(EventJson.write(approved())).isEqualTo(
                "{\"amountWei\":\"400000000000000000\",\"approvals\":[]," + "\"destination\":\"" + DESTINATION + "\","
                        + "\"withdrawalId\":\"" + WITHDRAWAL + "\"}");
    }

    @Test
    void anEnvelopeHasExactlyOneEncoding() {
        // Note occurredAt: ISO-8601 text, not an epoch decimal. A decimal number of seconds is a
        // float, and floats are banned in this codebase for the reason the ledger is in wei.
        assertThat(EventJson.write(envelope())).isEqualTo(
                "{\"aggregateId\":\"" + WITHDRAWAL + "\"," + "\"eventId\":\"" + EVENT + "\","
                        + "\"eventType\":\"WithdrawalApproved\"," + "\"occurredAt\":\"2026-09-24T18:00:00Z\","
                        + "\"payload\":{\"amountWei\":\"400000000000000000\",\"approvals\":[]," + "\"destination\":\""
                        + DESTINATION + "\"," + "\"withdrawalId\":\"" + WITHDRAWAL + "\"}}");
    }

    @Test
    void theSameFactsAlwaysProduceTheSameBytes() {
        assertThat(EventJson.canonicalBytes(approved())).isEqualTo(EventJson.canonicalBytes(approved()));
    }

    @Test
    void anAmountCrossesTheWireAsAStringBecauseAJsonNumberIsADouble() {
        // 4 * 10^17 is comfortably past 2^53, where a JavaScript number stops being able to hold
        // consecutive integers. Quoted, it survives any parser.
        assertThat(EventJson.write(approved())).contains("\"amountWei\":\"400000000000000000\"");
    }

    @Test
    void aBigIntegerSurvivesTheRoundTripExactly() {
        BigInteger huge = BigInteger.TWO.pow(200);
        WithdrawalApproved original = new WithdrawalApproved(WITHDRAWAL, DESTINATION, huge, List.of());

        WithdrawalApproved parsed = EventJson.read(EventJson.write(original), WithdrawalApproved.class);

        assertThat(parsed.amountWei()).isEqualTo(huge);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void anEnvelopeSurvivesTheRoundTripWithItsPayload() {
        EventEnvelope parsed = EventJson.read(EventJson.write(envelope()), EventEnvelope.class);

        assertThat(parsed).isEqualTo(envelope());
        assertThat(parsed.payloadAs(WithdrawalApproved.class)).isEqualTo(approved());
    }

    @Test
    void aFieldThisVersionHasNeverHeardOfIsIgnoredRatherThanFatal() {
        // The other half of the schema-evolution rule: a producer adds an optional field, and a
        // consumer built before it existed keeps working instead of dead-lettering every message.
        String withAnExtraField = """
                {"withdrawalId":"11111111-1111-1111-1111-111111111111",\
                "destination":"0x70997970c51812dc3a010c7d01b50e0d17dc79c8",\
                "amountWei":"400000000000000000","approvals":[],"feeCapWei":"21000"}""";

        WithdrawalApproved parsed = EventJson.read(withAnExtraField, WithdrawalApproved.class);

        assertThat(parsed.amountWei()).isEqualTo(POINT_FOUR_ETH);
    }

    @Test
    void textThatIsNotEvenJsonIsAnEventFormatException() {
        assertThatThrownBy(() -> EventJson.read("{not json", EventEnvelope.class))
                .isInstanceOf(EventFormatException.class)
                .hasMessageContaining("EventEnvelope");
    }

    @Test
    void theFailureMessageDoesNotQuoteTheMessageItFailedOn() {
        // A parse failure's message ends up in a log line and alongside a dead-lettered record. The
        // input it failed on is an event payload, which carries destinations and amounts; the type
        // is enough to find the message, and the cause has the detail for whoever opens it.
        assertThatThrownBy(() -> EventJson.read("{\"destination\":\"" + DESTINATION + "\"", WithdrawalApproved.class))
                .isInstanceOf(EventFormatException.class)
                .hasMessageNotContaining(DESTINATION)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void aPayloadOfTheWrongShapeIsAnEventFormatException() {
        // The payload really is a WithdrawalApproved. Asking for the other shape must fail rather
        // than quietly hand back a record whose txHash is null.
        assertThatThrownBy(() -> envelope().payloadAs(WithdrawalBroadcast.class))
                .isInstanceOf(EventFormatException.class)
                .hasMessageContaining("WithdrawalBroadcast");
    }

    @Test
    void anEventTypeThisVersionDoesNotKnowIsRefusedRatherThanReadAsNull() {
        String fromTheFuture = EventJson.write(envelope()).replace("WithdrawalApproved", "WithdrawalUnheardOf");

        assertThatThrownBy(() -> EventJson.read(fromTheFuture, EventEnvelope.class))
                .isInstanceOf(EventFormatException.class);
    }

    private static WithdrawalApproved approved() {
        return new WithdrawalApproved(WITHDRAWAL, DESTINATION, POINT_FOUR_ETH, List.of());
    }

    private static EventEnvelope envelope() {
        return EventEnvelope.of(EVENT, EventType.WITHDRAWAL_APPROVED, NOON, WITHDRAWAL, approved());
    }
}
