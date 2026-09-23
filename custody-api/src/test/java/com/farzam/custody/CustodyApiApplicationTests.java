package com.farzam.custody;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M0 acceptance test.
 *
 * <p>{@code @SpringBootTest} boots the entire application context, exactly as
 * {@code main()} would. If a bean is missing, a {@code @Value} has no property, or
 * Hibernate's {@code ddl-auto: validate} finds an entity that does not match the
 * schema, this test fails. It is the cheapest broad regression test you get.
 *
 * <p>{@code @Testcontainers} + {@code @Container} start a throwaway Docker container
 * for the test class and stop it afterwards. {@code @ServiceConnection} is the part
 * that saves real work: it reads the container's randomly-assigned host port and
 * injects the JDBC URL, username and password into the context. No
 * {@code @DynamicPropertySource} boilerplate, and no clash with the Postgres from
 * docker-compose that may already be on 5432.
 *
 * <p>Why a real Postgres and not H2? Because this project depends on Postgres
 * behaviour that H2 does not have: {@code FOR UPDATE SKIP LOCKED}, {@code jsonb},
 * partial indexes, {@code numeric(78,0)}. A test against H2 would pass while
 * production broke.
 *
 * <p>The container is {@code static}, so one Postgres is shared by every test in the
 * class rather than started per test method.
 */
@SpringBootTest
@Testcontainers
class CustodyApiApplicationTests {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Testcontainers closes it via the JUnit extension
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

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
