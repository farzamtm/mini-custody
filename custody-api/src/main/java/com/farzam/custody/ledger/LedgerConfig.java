package com.farzam.custody.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Chooses how the ledger defends against lost updates.
 *
 * <p>{@code ledger.locking} is {@code pessimistic} or {@code optimistic}, and defaults to
 * pessimistic. Both strategies are real, tested code rather than one being a sketch: the same
 * fifty-thread test runs against each, which is the only way the comparison in ADR 0001 means
 * anything.
 *
 * <p>Wiring them here as {@code @Bean} methods rather than annotating the classes
 * {@code @Component} keeps the choice in one readable place, and keeps the two implementations
 * free of Spring annotations so they can be constructed directly in a test.
 */
@Configuration(proxyBeanMethods = false)
class LedgerConfig {

    /** {@code matchIfMissing}: no configuration at all means the safe option, not no option. */
    @Bean
    @ConditionalOnProperty(name = "ledger.locking", havingValue = "pessimistic", matchIfMissing = true)
    BalanceUpdater pessimisticBalanceUpdater(JdbcClient jdbc) {
        return new PessimisticBalanceUpdater(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "ledger.locking", havingValue = "optimistic")
    BalanceUpdater optimisticBalanceUpdater(JdbcClient jdbc) {
        return new OptimisticBalanceUpdater(jdbc);
    }
}
