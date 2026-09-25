package com.farzam.custody;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.WithoutKafka;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

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
@WithoutKafka
@SpringBootTest
class CustodyApiApplicationTests extends AbstractPostgresTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        // Intentionally empty: the assertion is that startup did not throw.
        assertThat(dataSource).isNotNull();
    }

    /**
     * No background thread talks to the outside world in an ordinary test context.
     *
     * <p>A guard rather than a feature. {@code chain.rpc-url} defaults to
     * {@code http://localhost:8545}, which on a developer's machine is whatever
     * {@code docker compose up} left running — so a scheduled watcher polls a real Ethereum node
     * from every test in the module, and the results depend on whether the stack happens to be up.
     * {@code test/resources/application.properties} turns the timer off for exactly that reason,
     * and this asserts it, because deleting a properties file is a silent way to undo it: nothing
     * would fail, the suite would simply go back to reaching outside the repository.
     *
     * <p>The tests whose subject <em>is</em> the timer turn it back on per class, and
     * {@code ConfirmationSchedulingTest} would fail loudly if that stopped working.
     */
    @Test
    void theConfirmationWatchersTimerIsNotRunningByDefault() {
        // The bean carries the @Scheduled method and is @ConditionalOnProperty, so its absence is
        // the timer's absence — there is nothing else to assert and nothing subtler to get wrong.
        assertThat(context.containsBean("confirmationScheduling"))
                .as("the chain watcher's timer should be off unless a test asks for it")
                .isFalse();
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
