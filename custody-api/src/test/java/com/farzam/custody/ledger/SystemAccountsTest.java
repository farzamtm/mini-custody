package com.farzam.custody.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.WithoutKafka;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The three constants in {@link SystemAccounts} and the three rows in {@code V3__system_accounts.sql}
 * are the same three facts written twice. This is what keeps them the same.
 *
 * <p>A cheap test for a cheap duplication — the alternative would be reading the ids out of the
 * database at startup, which costs a query and a class to hold the result in order to avoid a
 * mistake this catches in milliseconds.
 */
@WithoutKafka
@SpringBootTest
class SystemAccountsTest extends AbstractPostgresTest {

    @Autowired
    private AccountRepository accounts;

    @Test
    void everySystemAccountIsSeededWithTheTypeItsConstantClaims() {
        Map<UUID, AccountType> expected = Map.of(
                SystemAccounts.EXTERNAL,
                AccountType.EXTERNAL,
                SystemAccounts.PENDING_OUT,
                AccountType.PENDING_OUT,
                SystemAccounts.BANK_OPERATING,
                AccountType.BANK_OPERATING);

        expected.forEach((id, type) -> {
            Account account = accounts.findById(id).orElseThrow(() -> new AssertionError("not seeded: " + type));
            assertThat(account.getType()).isEqualTo(type);
            assertThat(account.getClientId()).as("a system account belongs to nobody").isNull();
            assertThat(account.getAsset()).isEqualTo("ETH");
        });
    }

    @Test
    void onlyExternalIsAllowedToRunNegative() {
        assertThat(AccountType.EXTERNAL.mayGoNegative()).isTrue();
        assertThat(AccountType.PENDING_OUT.mayGoNegative()).isFalse();
        assertThat(AccountType.BANK_OPERATING.mayGoNegative()).isFalse();
        assertThat(AccountType.CLIENT.mayGoNegative()).isFalse();
    }
}
