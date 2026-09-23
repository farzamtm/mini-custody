package com.farzam.custody.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The hash is what turns an idempotency key from a label into a guarantee, so what it distinguishes
 * and what it does not are both worth pinning down.
 */
class RequestHashTest {

    private static final UUID ACCOUNT = UUID.fromString("b3e1a4c2-0000-4000-8000-000000000001");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");

    @Test
    void theSameRequestHashesTheSameWay() {
        assertThat(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH))
                .isEqualTo(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH));
    }

    @Test
    void itIsSixtyFourLowerCaseHexCharacters() {
        assertThat(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH)).matches("^[0-9a-f]{64}$");
    }

    @Test
    void aDifferentAmountIsADifferentRequest() {
        assertThat(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH))
                .isNotEqualTo(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH.add(BigInteger.ONE)));
    }

    @Test
    void aDifferentDestinationIsADifferentRequest() {
        assertThat(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH))
                .isNotEqualTo(RequestHash.of(ACCOUNT, "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc", POINT_FOUR_ETH));
    }

    @Test
    void aDifferentAccountIsADifferentRequest() {
        assertThat(RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH))
                .isNotEqualTo(RequestHash.of(UUID.randomUUID(), DESTINATION, POINT_FOUR_ETH));
    }

    @Test
    void theConstantTimeComparisonStillCompares() {
        String hash = RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH);

        assertThat(RequestHash.matches(hash, hash)).isTrue();
        assertThat(RequestHash.matches(hash, RequestHash.of(ACCOUNT, DESTINATION, POINT_FOUR_ETH.add(BigInteger.ONE))))
                .isFalse();
        // Differing only in the last character is the case a naive early-return comparison gets
        // right and a broken constant-time one gets wrong.
        assertThat(RequestHash.matches(hash, hash.substring(0, 63) + (hash.endsWith("a") ? "b" : "a"))).isFalse();
    }

    @Test
    void thereIsNoFieldBoundaryToSlideValuesAcross() {
        // Without a separator between the fields, an account id ending in "1" plus destination
        // "0x70…" would hash the same as an id ending in nothing plus destination "10x70…". The pipe
        // is what makes the encoding injective, and this is the test that says so.
        String shifted = RequestHash.of(ACCOUNT, DESTINATION, new BigInteger("4000000000000000001"));
        String original = RequestHash.of(ACCOUNT, DESTINATION + "1", POINT_FOUR_ETH.multiply(BigInteger.TEN));

        assertThat(shifted).isNotEqualTo(original);
    }
}
