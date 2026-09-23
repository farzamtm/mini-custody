package com.farzam.signer;

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

/** Same M0 check for the signer: context starts, Flyway builds the key-custody schema. */
@SpringBootTest
@Testcontainers
class SignerApplicationTests {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void flywayCreatesEveryTable() throws Exception {
        assertThat(publicTableNames()).contains("wallet_keys", "chain_nonces", "signing_log", "processed_events")
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
