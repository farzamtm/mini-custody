package com.farzam.custody.whitelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The equality contract of an {@code @IdClass}, which JPA relies on and nothing else calls directly.
 *
 * <p>Worth testing precisely because it is invisible: Hibernate uses this as the key of the
 * persistence context's identity map, so a wrong {@code equals} does not throw, it quietly makes the
 * same row load as two different objects — or two different rows as one.
 */
class WhitelistedAddressIdTest {

    private static final UUID CLIENT = UUID.randomUUID();
    private static final String ADDRESS = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Test
    void twoIdsWithTheSamePartsAreEqualAndHashAlike() {
        WhitelistedAddressId one = new WhitelistedAddressId(CLIENT, ADDRESS);
        WhitelistedAddressId other = new WhitelistedAddressId(CLIENT, ADDRESS);

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other).isEqualTo(one);
        assertThat(one.getClientId()).isEqualTo(CLIENT);
        assertThat(one.getAddress()).isEqualTo(ADDRESS);
    }

    @Test
    void differingInEitherPartMakesADifferentKey() {
        WhitelistedAddressId id = new WhitelistedAddressId(CLIENT, ADDRESS);

        assertThat(id).isNotEqualTo(new WhitelistedAddressId(UUID.randomUUID(), ADDRESS));
        assertThat(id).isNotEqualTo(new WhitelistedAddressId(CLIENT, "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc"));
        assertThat(id).isNotEqualTo(null).isNotEqualTo("not an id");
    }

    @Test
    void theNoArgConstructorJpaNeedsProducesAnEmptyKey() {
        // JPA instantiates the class reflectively before populating it, so this has to exist and has
        // to survive being compared before it is filled in.
        WhitelistedAddressId blank = new WhitelistedAddressId();

        assertThat(blank.getClientId()).isNull();
        assertThat(blank.getAddress()).isNull();
        assertThat(blank).isEqualTo(new WhitelistedAddressId()).isNotEqualTo(new WhitelistedAddressId(CLIENT, ADDRESS));
    }
}
