package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The equality contract of an {@code @IdClass}, which JPA relies on and nothing else calls directly.
 *
 * <p>Worth testing precisely because it is invisible, and more so here than for
 * {@link com.farzam.custody.whitelist.WhitelistedAddressId}: Hibernate uses this as the key of the
 * persistence context's identity map, so a wrong {@code equals} does not throw — it quietly makes
 * two approvals from different approvers look like one object. On the class whose primary key
 * <em>is</em> the four-eyes rule, that is the difference between a quorum and one person signing
 * twice.
 */
class ApprovalIdTest {

    private static final UUID WITHDRAWAL = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();

    @Test
    void twoIdsWithTheSamePartsAreEqualAndHashAlike() {
        ApprovalId one = new ApprovalId(WITHDRAWAL, APPROVER);
        ApprovalId other = new ApprovalId(WITHDRAWAL, APPROVER);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other).isEqualTo(one);
        assertThat(one.getWithdrawalId()).isEqualTo(WITHDRAWAL);
        assertThat(one.getApproverId()).isEqualTo(APPROVER);
    }

    @Test
    void differingInEitherPartMakesADifferentKey() {
        ApprovalId id = new ApprovalId(WITHDRAWAL, APPROVER);

        // Two approvers on one withdrawal: a quorum, and it depends on these being distinct keys.
        assertThat(id).isNotEqualTo(new ApprovalId(WITHDRAWAL, UUID.randomUUID()));
        // One approver on two withdrawals: also distinct, or approving one payment would look like
        // having approved another.
        assertThat(id).isNotEqualTo(new ApprovalId(UUID.randomUUID(), APPROVER));
        assertThat(id).isNotEqualTo(null).isNotEqualTo("not an id");
    }

    @Test
    void theNoArgConstructorJpaNeedsProducesAnEmptyKey() {
        // JPA instantiates the class reflectively before populating it, so this has to exist and has
        // to survive being compared before it is filled in.
        ApprovalId blank = new ApprovalId();

        assertThat(blank.getWithdrawalId()).isNull();
        assertThat(blank.getApproverId()).isNull();
        assertThat(blank).isEqualTo(new ApprovalId()).isNotEqualTo(new ApprovalId(WITHDRAWAL, APPROVER));
    }
}
