# 5. The outbox relay polls, and sends inside its transaction

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M4

## Context

When a withdrawal is approved, two things have to happen: the database records the
new status, and the signer is told. They are two systems with no transaction
between them, and doing them in sequence is wrong in both orders.

Commit first, then publish, and a crash in between leaves a withdrawal that is
`APPROVED` in the database and unknown to the signer. Nothing is broken enough to
alert on; the withdrawal simply never moves, and the client's funds stay held until
somebody reads the table by hand.

Publish first, then commit, and a failed commit leaves the signer acting on an
approval that the database says never happened. That one ends with a transaction on
a chain that cannot take it back.

The transactional outbox removes the choice: the event is written as a row in the
same transaction as the state change, so both are there or neither is. That much is
settled by the problem. What is left to decide is how the row becomes a message.

## Decision

**A polling relay, not change data capture.** `OutboxRelay` runs every 500 ms,
claims unpublished rows, sends them and marks them. The alternative is Debezium
reading Postgres's write-ahead log and publishing rows with no polling at all,
which is the better answer at scale: no query, no latency floor, no load on the
database from a job that usually finds nothing. It is not the answer here. It adds
a Kafka Connect cluster, a replication slot that will fill the disk if the
connector stops, and a second deployable to understand — against a relay that is
about eighty lines of Java and whose failure modes are visible in one file. The
polling cost is one indexed query twice a second against the partial index
`outbox_unpublished`, which contains only the backlog.

**`FOR UPDATE SKIP LOCKED`, so more than one instance is safe.** Two instances of
custody-api both tick. Plain `FOR UPDATE` would make the second block on the
first's rows and then publish them again when the lock was released — scaling out
would create duplicates. `SKIP LOCKED` steps over rows another transaction holds,
so each instance takes a disjoint batch and nobody waits.

**The send happens while the rows are locked.** This is the decision most worth
arguing with, because it holds a database transaction open across a network call.
The alternative is to claim rows in one transaction, mark them claimed, and send in
another. That shortens the lock and widens the window between "this row is mine"
and "this row is sent" — every crash in the wider window is a duplicate or, if the
row is marked before the send, a lost event. The lock is short and nobody is
queueing behind it, precisely because of `SKIP LOCKED`. Trading a duplicate for a
lock this repository does not contend on is not a trade worth making.

**A failed send stops the batch instead of skipping the row.** Kafka orders
messages within a partition, and the ordering that matters — everything about one
withdrawal, in sequence — is bought by keying on the withdrawal id. Skipping a row
that would not send and carrying on could publish a later event about the same
withdrawal before an earlier one. Rows already sent in that batch keep their marks,
because the loop breaks rather than throwing.

**The event id is the outbox row's primary key, generated once in the business
transaction.** Not per send attempt. A relay that minted a fresh id each time it
tried would make every redelivery look like a new event, and every consumer's
duplicate check would be decoration.

## Consequences

- **Delivery is at-least-once, and that is not a defect to be fixed later.** Between
  the broker's acknowledgement and the `UPDATE` that marks the row there is a window
  a crash can land in, and the next tick resends. The window cannot be closed — it is
  the dual-write problem again, one layer down — so every consumer carries
  `processed_events` instead. ADR 0006.
- **Head-of-line blocking is real.** One permanently unsendable row halts the relay,
  because the batch stops rather than stepping over it. In practice a send fails
  because the broker is unreachable, in which case the next row was not going
  anywhere either. A production system would add an attempt counter and move a row
  that has failed enough times to a parking table, which is a thing to build when
  there is evidence it happens rather than in advance.
- **Latency has a floor of one poll interval.** 500 ms before the signer hears about
  an approval. For a flow whose next step is three block confirmations, this does not
  register.
- **The relay's timer is a separate bean from the relay.** `@Transactional` works
  through a proxy, so a `@Scheduled` method calling the batch on `this` would run it
  with no transaction at all: no locks taken, and each row marked published by its own
  autocommitted statement. The split also lets tests drive one batch and assert on it
  instead of sleeping.
- **The dead-letter topic name is ours, not the framework's.** Spring Kafka appended
  `.DLT` for years and changed to `-dlt` in version 4. The recoverer logs a successful
  publication either way, so inheriting the default meant failed messages accumulating
  in an undeclared topic nobody was watching — found by a test, which is the only
  reason it was found. The destination is now resolved through `Topics.dlt`.
- **`jsonb` does not preserve bytes.** Postgres parses the payload, drops whitespace
  and stores keys in its own order, so what goes in does not come out byte for byte.
  Signature verification in M3 and M5 therefore cannot trust the stored bytes: each
  party parses into the shared payload record and re-serialises it canonically with
  `EventJson`. Storing the payload as `text` would preserve the bytes and give up
  querying it in SQL, and would make correctness depend on a column type anyone could
  migrate without knowing what they had broken.
