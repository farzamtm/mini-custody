package com.farzam.custody.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class EthereumAddressTest {

    private static final String LOWER = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final String CHECKSUMMED = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

    @Test
    void anEip55ChecksummedAddressIsTheSameAddress() {
        // The two spellings differ only in case, and EIP-55 uses that case as a checksum. A client
        // that whitelisted one and withdrew to the other means the same address both times, and a
        // whitelist that disagreed would be wrong rather than strict.
        assertThat(EthereumAddress.normalise(CHECKSUMMED)).isEqualTo(LOWER);
        assertThat(EthereumAddress.normalise(LOWER)).isEqualTo(LOWER);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {"", "0x70997970c51812dc3a010c7d01b50e0d17dc79c", // 39 hex characters
                    "0x70997970c51812dc3a010c7d01b50e0d17dc79c88", // 41
                    "70997970c51812dc3a010c7d01b50e0d17dc79c8", // no 0x
                    "0x70997970c51812dc3a010c7d01b50e0d17dc79cg", // g is not hex
                    "0X70997970c51812dc3a010c7d01b50e0d17dc79c8", // capital X
            })
    void anythingThatIsNotTwentyBytesOfHexIsRejected(String notAnAddress) {
        assertThatExceptionOfType(MalformedAddressException.class)
                .isThrownBy(() -> EthereumAddress.normalise(notAnAddress));
    }

    @Test
    void theRejectionDoesNotEchoTheInputBack() {
        // The value is attacker-controlled and on its way into a log line and an HTTP body.
        String hostile = "0x<script>alert(1)</script>";

        assertThatExceptionOfType(MalformedAddressException.class).isThrownBy(() -> EthereumAddress.normalise(hostile))
                .withMessageNotContaining("script");
    }
}
