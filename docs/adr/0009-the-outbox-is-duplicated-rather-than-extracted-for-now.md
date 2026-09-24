# 0009 — The outbox is duplicated rather than extracted, for now

Status: accepted (M5), to be revisited

## Context

M4 gave custody-api a transactional outbox: `OutboxWriter` writes a row in the
business transaction, `OutboxRelay` claims a batch with `FOR UPDATE SKIP LOCKED`,
publishes it and marks it sent. M5 gives the signer the same problem — it has to
write `signing_log` and "publish `WithdrawalBroadcast`" atomically, or a crash
between them leaves a spent nonce, a signed transaction, possibly a payment on
chain, and a custody-api that never hears about any of it.

So the signer needs an outbox, and there is now a second copy of about two hundred
lines that are not merely similar but identical in every decision that matters:
the claim query, the ordering tiebreaker, waiting on the broker's acknowledgement
before marking a row, and stopping the batch rather than skipping a row.

Two of those lines are load-bearing in a way that makes duplication genuinely
risky. M4's pull request records two bugs found in that relay — the dead-letter
suffix that Spring Kafka 4 changed underneath it, and a `KafkaException` thrown on
the calling thread when no broker is reachable, which escaped the batch loop and
un-marked every row already published. A second copy is a second place for those
fixes to be absent.

## Decision

Duplicate it in this milestone, and do not extract a shared module as part of M5.

The signer gets `com.farzam.signer.outbox` with its own writer, relay and
scheduling, and `V2__outbox.sql` creating a table identical to custody-api's. Each
copy cross-references the other and this ADR.

The reason is not that duplication is fine. It is that extracting it properly is a
larger change than it looks, and bundling it into a milestone would make both
harder to review:

**The relay's tests are context-bound.** `OutboxRelayIntegrationTest` and
`OutboxRelayFailureTest` run against a full custody-api Spring context, its Flyway
migrations and its test support. A `platform` module holding the production code
and leaving the tests behind would have zero coverage and fail the per-module
JaCoCo floor on the first build. Moving them means the new module needs its own
test application, its own migrations for `outbox` and `processed_events`, and its
own Testcontainers harness — a real piece of work, and one with nothing to do with
signing.

**It is a fourth module.** The README's three-module table, the settings file, the
architecture section and the dependency wiring all describe a shape that says what
this system is. Changing that shape should be its own change with its own
argument, not a side effect of a milestone about keys.

## Consequences

**A bug fixed in one relay will not be fixed in the other.** That is the cost, it
is stated plainly, and it is the thing that makes this temporary rather than a
settled position. The two copies are in sync today; nothing enforces that
tomorrow.

**The duplication is visible rather than quiet.** Both files say the other exists
and point here, and the README's production-differences section lists it. A future
reader finding two relays should find the reason in the same minute.

**The `outbox` tables are separate, which is correct regardless.** The two services
have separate databases on purpose — the signer's `wallet_keys` must not be
readable from custody-api and vice versa — so even a shared module would leave two
tables. Only the Java is duplicated.

## When to revisit

Whichever comes first:

- A third producer needs an outbox. Two is a coincidence; three is a pattern with
  an obvious name.
- The two copies need to diverge for a reason other than the topic they publish
  to. That is the signal that they were never the same thing, and the answer is to
  stop pretending rather than to extract.
- A bug is found in one of them. Fixing it twice is the moment the cost becomes
  concrete, and the right response is to extract first and fix once.

The extraction itself is a `platform` module holding `OutboxWriter`,
`OutboxRelay`, `OutboxScheduling` and `ProcessedEvents`, with its own test
application and migrations, and both services adding it to their component scan.
Topic declarations stay per-service: those genuinely differ.

## Alternatives rejected

**Put it in `common`.** That module has no Spring dependency, deliberately, so
that the event contract cannot quietly grow service logic. Adding `spring-jdbc` and
`spring-kafka` to it would delete the property that makes it worth having, to avoid
a duplication that is already written down.

**Give the signer no outbox and publish after committing.** Removes the
duplication by removing the pattern, and reintroduces exactly the dual write M4
existed to fix — in the place where losing the event leaves a client's funds held
indefinitely with no error anywhere. Not a trade worth making to save two hundred
lines.

**Drive results off `signing_log` instead of a generic outbox.** A
`WithdrawalBroadcast` is fully derivable from a `signing_log` row, so a
`result_published_at` column would do the job for the success path without a new
table. It does nothing for refusals, which have no signature and therefore no row,
and inventing a second mechanism for those is more moving parts than the table it
avoided.
