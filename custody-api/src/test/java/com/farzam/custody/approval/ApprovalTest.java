package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The stored evidence cannot be edited through a reference to it.
 *
 * <p>Both directions of the copy matter and neither is decoration. An {@link Approval} holds the
 * signature that proves somebody agreed to a payment; if the array it was built from were shared,
 * the caller could change the evidence after it was recorded, and if the array it hands out were
 * shared, a reader could change it in place. Java arrays are mutable and a {@code final} field does
 * nothing about it, so the guard has to be the copy — and a copy is exactly the kind of thing that
 * gets "simplified" away by somebody who has not thought about what the field is.
 */
class ApprovalTest {

    private static final UUID WITHDRAWAL = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();

    @Test
    void changingTheArrayItWasBuiltFromDoesNotChangeTheApproval() {
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 7);
        Approval approval = Approval.of(WITHDRAWAL, APPROVER, signature);

        Arrays.fill(signature, (byte) 0);

        assertThat(approval.getSignature()).containsOnly((byte) 7);
    }

    @Test
    void changingWhatItHandsOutDoesNotChangeTheApproval() {
        byte[] signature = new byte[64];
        Arrays.fill(signature, (byte) 7);
        Approval approval = Approval.of(WITHDRAWAL, APPROVER, signature);

        Arrays.fill(approval.getSignature(), (byte) 0);

        assertThat(approval.getSignature()).containsOnly((byte) 7);
    }

    @Test
    void anApprovalKnowsWhatItIsAndWhen() {
        Approval approval = Approval.of(WITHDRAWAL, APPROVER, new byte[64]);

        assertThat(approval.getWithdrawalId()).isEqualTo(WITHDRAWAL);
        assertThat(approval.getApproverId()).isEqualTo(APPROVER);
        assertThat(approval.getCreatedAt()).isNotNull();
    }
}
