package com.farzam.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What approvers sign, and the properties a signature over it depends on. */
class ApprovalStatementTest {

    private static final UUID WITHDRAWAL = UUID.fromString("0f0d4e04-39ba-4a4f-9a09-1a0e8e5cf2f3");

    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    private static final BigInteger AMOUNT = new BigInteger("400000000000000000");

    @Test
    void theCanonicalFormHasSortedKeysNoWhitespaceAndTheAmountAsAString() {
        String json = new String(
                new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT).canonicalBytes(),
                StandardCharsets.UTF_8);

        assertThat(json).isEqualTo(
                "{\"amountWei\":\"" + AMOUNT + "\",\"destination\":\"" + DESTINATION + "\",\"withdrawalId\":\""
                        + WITHDRAWAL + "\"}");
    }

    /**
     * The property the whole scheme rests on. Two parties that derive different bytes from the same
     * facts cannot agree on a signature, and the failure looks identical to a forgery.
     */
    @Test
    void theSameFactsAlwaysProduceTheSameBytes() {
        assertThat(new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT).canonicalBytes())
                .isEqualTo(new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT).canonicalBytes());
    }

    /**
     * And its converse: changing anything a person was shown changes what they signed. A destination
     * edited after approval has to invalidate the signature, or approving a withdrawal would be
     * approving whatever it later became.
     */
    @Test
    void changingAnyFieldChangesTheBytes() {
        byte[] original = new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT).canonicalBytes();

        assertThat(new ApprovalStatement(UUID.randomUUID(), DESTINATION, AMOUNT).canonicalBytes())
                .isNotEqualTo(original);
        assertThat(new ApprovalStatement(WITHDRAWAL, "0x" + "a".repeat(40), AMOUNT).canonicalBytes())
                .isNotEqualTo(original);
        assertThat(new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT.add(BigInteger.ONE)).canonicalBytes())
                .isNotEqualTo(original);
    }

    /**
     * The statement is derived from the event, so a tampered event produces a statement nobody
     * signed rather than one that happens to match what the signer expected.
     */
    @Test
    void theStatementIsReadOffTheEventsOwnFields() {
        var event = new WithdrawalApproved(WITHDRAWAL, DESTINATION, AMOUNT, List.of());

        assertThat(ApprovalStatement.of(event)).isEqualTo(new ApprovalStatement(WITHDRAWAL, DESTINATION, AMOUNT));
    }

    @Test
    void everyFieldIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new ApprovalStatement(null, DESTINATION, AMOUNT));
        assertThatNullPointerException().isThrownBy(() -> new ApprovalStatement(WITHDRAWAL, null, AMOUNT));
        assertThatNullPointerException().isThrownBy(() -> new ApprovalStatement(WITHDRAWAL, DESTINATION, null));
    }
}
