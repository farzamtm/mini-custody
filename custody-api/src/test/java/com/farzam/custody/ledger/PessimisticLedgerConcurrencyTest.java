package com.farzam.custody.ledger;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The fifty-thread race against {@code SELECT … FOR UPDATE}, the configured default.
 *
 * <p>No {@code properties} here on purpose: this runs the ledger exactly as
 * {@code application.yml} ships it, and reuses the Spring context the other default-configuration
 * tests already built.
 */
@SpringBootTest
class PessimisticLedgerConcurrencyTest extends AbstractLedgerConcurrencyTest {}
