# 6. Consumers are idempotent, because delivery is at-least-once

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M4

## Context

Kafka redelivers. Not rarely, and not only when something is broken:

- The outbox relay resends any row it published but crashed before marking (ADR 0005).
- A consumer that dies between handling a message and committing its offset is handed
  the message again on restart.
- A rebalance — a consumer joining, leaving or being declared dead — reassigns
  partitions, and whatever was in flight is redelivered to the new owner.
- The producer retries a send whose acknowledgement was lost.

Only the last of those is addressed by `enable.idempotence=true`, which deduplicates
a producer's own retries at the broker. The other three happen above that layer and
are invisible to it.

For custody-api the message being redelivered is `WithdrawalSigningFailed`, and
applying it twice means releasing the same hold twice: the client is credited 0.4 ETH
they never had, `PENDING_OUT` goes 0.4 ETH short, and the ledger still balances
perfectly, because two well-formed postings were made instead of one. Double-entry
bookkeeping catches a posting that does not balance. It does not catch a posting that
should not have happened.

## Decision

**The event id goes into `processed_events` in the same transaction as the state
change.** One transaction covers the insert, the withdrawal's new status and the
ledger posting. There is no state in which the event is recorded as handled but its
effect is missing, or the reverse. A redelivery inserts nothing and returns.

**`insert ... on conflict do nothing`, and not a caught primary-key violation.** The
obvious alternative does not work in Postgres: a constraint violation aborts the
whole transaction, so every statement after the catch fails with "current transaction
is aborted". The handler would have caught the exception and still have nothing left
to do but roll back. Asking the database not to raise it is the only version that
leaves a usable transaction. The same reasoning is in `JournalWriter`, for the same
reason.

**The check comes before the payload is parsed.** It only needs the envelope, and an
event already applied should cost one indexed insert that does nothing.

**The consumer name is a column, not a global key space.** Two consumers in this
service that both care about one event each get to handle it once. A global key space
would let whichever ran first silence the other. The name is the Kafka group id, held
in one constant so the two cannot drift.

**Idempotency is layered rather than solved once.** `processed_events` protects the
state machine; the ledger's `unique (kind, reference_id)` independently protects the
balance, so a `WITHDRAWAL_RELEASE` cannot be booked twice however it is reached —
including by M6 releasing the same hold for a different reason. Neither makes the
other redundant. The API has its idempotency key (ADR 0003) and the signer will have
the primary key on `signing_log` (M5). Every layer sees duplicates, so every layer
handles them.

**A message that cannot be handled is retried three times and then dead-lettered.**
Without that, a poison message is redelivered forever and blocks its partition, and
every withdrawal that hashes to that partition stops behind it. Deserialisation
failures are classified non-retryable and go straight to `<topic>.DLT`: invalid JSON
will be exactly as invalid in two seconds, and three attempts only delay the moment
somebody looks at it.

## Consequences

- **Exactly-once effects, from at-least-once delivery.** Which is the only kind of
  exactly-once available: Kafka transactions give exactly-once for read-process-write
  *within Kafka*, and cover no side effect outside it — not a database write, not an
  HTTP call, not signing a transaction.
- **A failed message rolls back its own `processed_events` row**, so a retry is a real
  retry rather than a replay that has already marked itself done. This falls out of
  the two being one transaction, but it is the reason the order cannot be reversed.
- **`processed_events` grows without bound.** It needs a retention job — delete rows
  older than the topic's retention, beyond which no redelivery is possible. Not
  written: at this volume the table is smaller than the index on it, and a cleanup job
  nobody needs yet is a cleanup job nobody will test.
- **The offset is committed outside the transaction**, by the container, after the
  listener returns. That is the remaining dual write and it is deliberate: its failure
  mode is a redelivery, which is the case already handled.
- **An unknown event type is dead-lettered rather than skipped.** A consumer meeting a
  type it has never heard of is either a deployment ordering problem or a producer on
  the wrong topic. Ignoring it quietly loses data that nobody discovers until
  reconciliation disagrees with the chain; the dead-letter topic keeps it replayable
  once the version that understands it is deployed. Unknown *fields*, by contrast, are
  ignored on purpose — that is what makes adding an optional field a safe change.
