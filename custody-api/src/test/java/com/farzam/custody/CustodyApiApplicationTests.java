package com.farzam.custody;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.support.AbstractPostgresTest;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M0 acceptance test.
 *
 * <p>{@code @SpringBootTest} boots the entire application context, exactly as {@code main()} would.
 * If a bean is missing, a {@code @Value} has no property, or Hibernate's {@code ddl-auto: validate}
 * finds an entity that does not match the schema, this test fails. It is the cheapest broad
 * regression test you get, and from M1 on it is also the thing that catches an entity drifting away
 * from its Flyway migration.
 *
 * <p>The Postgres container comes from {@link AbstractPostgresTest}, shared with the ledger tests.
 */
@SpringBootTest
class CustodyApiApplicationTests extends AbstractPostgresTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        // Intentionally empty: the assertion is that startup did not throw.
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayCreatesEveryTable() throws Exception {
        assertThat(publicTableNames())
                .contains(
                        "accounts",
                        "journal_transactions",
                        "journal_entries",
                        "whitelisted_addresses",
                        "withdrawals",
                        "approvers",
                        "approvals",
                        "outbox",
                        "processed_events")
                // Flyway's own bookkeeping table: proof the migration ran through
                // Flyway rather than Hibernate quietly generating the schema.
                .contains("flyway_schema_history");
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
