package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * The quorum rule, without a database or a Spring context.
 *
 * <p>It is four lines of code and it decides how many people have to agree before money leaves, so
 * the boundary is worth pinning down rather than inferring from an integration test that happens to
 * use amounts on either side of it.
 */
class ApprovalPropertiesTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");

    private final ApprovalProperties properties = new ApprovalProperties(ONE_ETH);

    @Test
    void belowTheThresholdOneApproverIsEnough() {
        assertThat(properties.requiredApprovals(ONE_ETH.subtract(BigInteger.ONE))).isEqualTo(1);
        assertThat(properties.requiredApprovals(BigInteger.ONE)).isEqualTo(1);
    }

    /** "From 1 ETH up" includes 1 ETH. An off-by-one here is a withdrawal approved by one person. */
    @Test
    void theThresholdItselfNeedsTwo() {
        assertThat(properties.requiredApprovals(ONE_ETH)).isEqualTo(2);
    }

    @Test
    void aboveTheThresholdNeedsTwo() {
        assertThat(properties.requiredApprovals(ONE_ETH.multiply(BigInteger.TEN))).isEqualTo(2);
    }

    /**
     * A deployment that forgot to configure a quorum asks for two approvers on everything.
     *
     * <p>Fail-closed, and in the strict direction: the other reading of an absent property is a
     * system that quietly accepts one signature for any amount, which is the same bug with nobody
     * noticing. The same argument as {@code PolicyProperties} in the signer.
     */
    @Test
    void anUnconfiguredThresholdMeansEverythingNeedsTwo() {
        ApprovalProperties unconfigured = new ApprovalProperties(null);

        assertThat(unconfigured.requiredApprovals(BigInteger.ONE)).isEqualTo(2);
        assertThat(unconfigured.secondApprovalFromWei()).isEqualTo(BigInteger.ZERO);
    }
}
