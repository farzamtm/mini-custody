package com.farzam.custody.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres container, shared by every integration test in the module.
 *
 * <p>Why a real Postgres and not H2: this project depends on behaviour H2 does not have —
 * {@code FOR UPDATE}, {@code FOR UPDATE SKIP LOCKED}, {@code on conflict do nothing}, {@code jsonb},
 * partial indexes, {@code numeric(78,0)}. A test against H2 would pass while production broke, which
 * is worse than no test.
 *
 * <p>Why a static container started in an initialiser rather than JUnit's {@code @Container}: the
 * annotation gives one container per test class, and the ledger tests need two Spring contexts (one
 * per locking strategy). Starting it once here means one container for the whole run. Testcontainers'
 * Ryuk sidecar removes it when the JVM exits, so nothing leaks.
 *
 * <p>Tests share the database, so they must not share data. Every test below creates its accounts
 * with fresh random ids.
 */
public abstract class AbstractPostgresTest {

    @SuppressWarnings("resource") // stopped by Ryuk at JVM exit, by design
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static {
        POSTGRES.start();
    }

    /**
     * Points Spring's DataSource at the container's randomly-assigned host port.
     *
     * <p>Random, so the Postgres your {@code docker compose up} may already have on 5432 is not in
     * the way.
     *
     * @param registry Spring's test property registry
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
