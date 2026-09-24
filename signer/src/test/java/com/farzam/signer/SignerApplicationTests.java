package com.farzam.signer;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.signer.crypto.WalletKeys;
import com.farzam.signer.support.AbstractSignerTest;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The context starts, Flyway builds the key-custody schema, and the hot wallet key is imported. */
@SpringBootTest
class SignerApplicationTests extends AbstractSignerTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private WalletKeys walletKeys;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayCreatesEveryTable() throws Exception {
        assertThat(publicTableNames())
                .contains("wallet_keys", "chain_nonces", "signing_log", "processed_events", "outbox")
                .contains("flyway_schema_history");
    }

    /**
     * The startup importer ran, which is what makes every other test in this module possible: with
     * no key in the store the signer would refuse everything with {@code UnknownWalletException}
     * rather than with a policy decision, and the refusal tests would pass for the wrong reason.
     */
    @Test
    void theHotWalletKeyIsSealedIntoTheStoreAtStartup() {
        assertThat(walletKeys.contains(HOT_WALLET_ADDRESS)).isTrue();
    }

    private List<String> publicTableNames() throws Exception {
        var names = new ArrayList<String>();
        try (var connection = dataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
        }
        return names;
    }
}
