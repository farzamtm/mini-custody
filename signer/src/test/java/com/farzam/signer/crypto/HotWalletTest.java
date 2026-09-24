package com.farzam.signer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Normalisation, and the one method here that exists purely to keep a secret out of a log. */
class HotWalletTest {

    private static final String CHECKSUMMED = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    private static final String LOWER_CASE = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void anAddressIsFoldedToLowerCaseSoEverythingDownstreamCanUseEquals() {
        assertThat(new HotWallet(" " + CHECKSUMMED + " ", "").address()).isEqualTo(LOWER_CASE);
    }

    @Test
    void absentPropertiesBecomeEmptyRatherThanNull() {
        HotWallet unset = new HotWallet(null, null);

        assertThat(unset.address()).isEmpty();
        assertThat(unset.hasKeyToImport()).isFalse();
    }

    @Test
    void aWalletWithNoAddressCannotBeSignedFrom() {
        assertThatThrownBy(() -> new HotWallet("", "").requireAddress()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signer.hot-wallet.address is not set");
    }

    /**
     * The record's generated {@code toString} would print the private key, and Spring reaches for
     * {@code toString} on a properties bean when binding fails and when actuator renders configprops.
     * A log is the part of a system that is deliberately copied somewhere else and kept.
     */
    @Test
    void theToStringNeverContainsThePrivateKey() {
        String secret = "ab".repeat(32);

        String rendered = new HotWallet(LOWER_CASE, secret).toString();

        assertThat(rendered).doesNotContain(secret).contains(LOWER_CASE).contains("<set>");
        assertThat(new HotWallet(LOWER_CASE, "").toString()).contains("<unset>");
    }
}
