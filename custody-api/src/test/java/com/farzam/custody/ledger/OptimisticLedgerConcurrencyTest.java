package com.farzam.custody.ledger;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The same fifty-thread race against version-check-and-retry.
 *
 * <p>It passes, which is the point: optimistic locking is correct here too. What it costs is
 * visible in the log rather than in the assertions — with fifty writers on one row, most attempts
 * find the version has moved and roll back, so the ten winners are paid for with dozens of wasted
 * transactions. That is the trade ADR 0001 decides.
 *
 * <p>Overriding the property builds a second Spring context, which is why the two strategies live
 * in separate classes rather than one parameterised test.
 */
@SpringBootTest(properties = "ledger.locking=optimistic")
class OptimisticLedgerConcurrencyTest extends AbstractLedgerConcurrencyTest {}
