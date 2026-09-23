package com.farzam.custody;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for custody-api.
 *
 * <p>{@code @SpringBootApplication} is three annotations in one:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} — this class may declare {@code @Bean} methods.
 *   <li>{@code @EnableAutoConfiguration} — look at what is on the classpath and configure
 *       it. A Postgres driver plus a JDBC URL becomes a {@code DataSource}; JPA on the
 *       classpath becomes an {@code EntityManager} and a transaction manager. This is the
 *       "convention over configuration" that Symfony's bundles do with recipes.
 *   <li>{@code @ComponentScan} — scan THIS package and everything below it for
 *       {@code @Service}, {@code @RestController}, {@code @Repository} and friends.
 * </ul>
 *
 * <p>That last point is why this class must stay in the root package
 * {@code com.farzam.custody}: put it in a sub-package and Spring silently stops
 * finding half your beans.
 *
 * <p>{@code @EnableScheduling} switches on the {@code @Scheduled} support that the
 * outbox relay (M4) and the confirmation watcher (M6) need.
 */
@SpringBootApplication
@EnableScheduling
public class CustodyApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustodyApiApplication.class, args);
    }
}
