package com.farzam.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The contract's own rules: the names on the wire, and what a payload refuses to be built without. */
class EventContractTest {

    private static final UUID WITHDRAWAL = UUID.randomUUID();
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final BigInteger ONE_WEI = BigInteger.ONE;

    // ---- Names that cannot change without a new topic version --------------

    @Test
    void theWireNamesArePascalCaseWhateverTheJavaConstantsAreCalled() {
        assertThat(EventType.WITHDRAWAL_APPROVED.wireName()).isEqualTo("WithdrawalApproved");
        assertThat(EventType.WITHDRAWAL_BROADCAST.wireName()).isEqualTo("WithdrawalBroadcast");
        assertThat(EventType.WITHDRAWAL_SIGNING_FAILED.wireName()).isEqualTo("WithdrawalSigningFailed");
    }

    @Test
    void everyWireNameReadsBackAsItsOwnType() {
        for (EventType type : EventType.values()) {
            assertThat(EventType.ofWireName(type.wireName())).isEqualTo(type);
        }
    }

    @Test
    void aNameNoTypeHasIsRefused() {
        assertThatThrownBy(() -> EventType.ofWireName("WithdrawalTeleported")).isInstanceOf(EventFormatException.class)
                .hasMessageContaining("WithdrawalTeleported");
    }

    @Test
    void theTopicNamesAreTheOnesBothServicesAgreedOn() {
        assertThat(Topics.WITHDRAWALS).isEqualTo("custody.withdrawals.v1");
        assertThat(Topics.SIGNER_RESULTS).isEqualTo("signer.results.v1");
        assertThat(Topics.dlt(Topics.SIGNER_RESULTS)).isEqualTo("signer.results.v1.DLT");
    }

    // ---- What a payload will not be built without --------------------------

    @Test
    void aWithdrawalApprovedWithoutItsFactsIsNotAWithdrawalApproved() {
        assertThatThrownBy(() -> new WithdrawalApproved(null, DESTINATION, ONE_WEI, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalApproved(WITHDRAWAL, null, ONE_WEI, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalApproved(WITHDRAWAL, DESTINATION, null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalApproved(WITHDRAWAL, DESTINATION, ONE_WEI, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theApprovalsListIsCopiedSoTheEvidenceCannotChangeAfterTheEventIsBuilt() {
        var mutable = new ArrayList<WithdrawalApproved.Approval>();
        mutable.add(new WithdrawalApproved.Approval(UUID.randomUUID(), "cHVibGlj", "c2ln"));
        WithdrawalApproved event = new WithdrawalApproved(WITHDRAWAL, DESTINATION, ONE_WEI, mutable);

        mutable.add(new WithdrawalApproved.Approval(UUID.randomUUID(), "b3RoZXI=", "b3RoZXJzaWc="));

        assertThat(event.approvals()).hasSize(1);
        assertThat(event.approvals()).isUnmodifiable();
    }

    @Test
    void anApprovalWithoutItsSignatureIsNotAnApproval() {
        assertThatThrownBy(() -> new WithdrawalApproved.Approval(null, "cHVibGlj", "c2ln"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalApproved.Approval(UUID.randomUUID(), null, "c2ln"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalApproved.Approval(UUID.randomUUID(), "cHVibGlj", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aBroadcastWithoutAHashSaysNothingWorthSaying() {
        assertThat(new WithdrawalBroadcast(WITHDRAWAL, "0xabc").txHash()).isEqualTo("0xabc");
        assertThatThrownBy(() -> new WithdrawalBroadcast(WITHDRAWAL, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalBroadcast(null, "0xabc")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aFailureWithoutAReasonLeavesNobodyAbleToAct() {
        assertThat(new WithdrawalSigningFailed(WITHDRAWAL, "quorum not met").reason()).isEqualTo("quorum not met");
        assertThatThrownBy(() -> new WithdrawalSigningFailed(WITHDRAWAL, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WithdrawalSigningFailed(null, "why")).isInstanceOf(NullPointerException.class);
    }

    // ---- The envelope ------------------------------------------------------

    @Test
    void anEnvelopeMissingAnyFieldIsRefusedRatherThanPublished() {
        // An envelope with a null eventId is one no consumer can deduplicate, and it would only be
        // discovered as a double spend.
        assertThatThrownBy(
                () -> new EventEnvelope(
                        null,
                        EventType.WITHDRAWAL_APPROVED,
                        Instant.now(),
                        WITHDRAWAL,
                        EventJson.toNode(approved())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new EventEnvelope(
                        UUID.randomUUID(),
                        null,
                        Instant.now(),
                        WITHDRAWAL,
                        EventJson.toNode(approved())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new EventEnvelope(
                        UUID.randomUUID(),
                        EventType.WITHDRAWAL_APPROVED,
                        null,
                        WITHDRAWAL,
                        EventJson.toNode(approved())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new EventEnvelope(
                        UUID.randomUUID(),
                        EventType.WITHDRAWAL_APPROVED,
                        Instant.now(),
                        null,
                        EventJson.toNode(approved())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new EventEnvelope(
                        UUID.randomUUID(),
                        EventType.WITHDRAWAL_APPROVED,
                        Instant.now(),
                        WITHDRAWAL,
                        null))
                .isInstanceOf(NullPointerException.class);
    }

    private static WithdrawalApproved approved() {
        return new WithdrawalApproved(WITHDRAWAL, DESTINATION, ONE_WEI, List.of());
    }
}
