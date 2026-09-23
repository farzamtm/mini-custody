# 3. Idempotency keys are backed by a unique index, not by a look-up

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M2

## Context

A client sends `POST /v1/withdrawals` and the connection times out. The client does
not know whether the request arrived: a timeout is silence, not a failure. Its only
sensible move is to retry, and if the server treats the retry as a new request the
client has withdrawn twice. On a blockchain the second transaction cannot be
recalled, so this is not an inconvenience to be smoothed over later — it is the
failure mode the endpoint exists to prevent.

The client therefore sends an `Idempotency-Key`, and the server has to decide what
"I have seen this key" means and how it finds out. The obvious implementation is:

```java
if (repository.findByClientIdAndIdempotencyKey(clientId, key).isPresent()) {
    return existing;
}
repository.save(newWithdrawal);
```

This is wrong in exactly the situation it was written for. The client retried
because the first attempt was slow, so both requests are in flight at the same
time. Both run the `SELECT`, both find nothing, both proceed to `INSERT`. Check-then-
act, with a gap in the middle that the retry is unusually likely to land in.

A second question sits behind the first. Even when the key is recognised, "the same
key" is not the same as "the same request". A client that recycles a key — a bug,
or a fixed key in a config file — would be handed back a withdrawal it did not ask
for, and told it had succeeded.

## Decision

**The unique index arbitrates, not the application.** `withdrawals` has
`unique (client_id, idempotency_key)`. `WithdrawalWriter` still does the look-up
first, because in the overwhelmingly common case it is cheaper and clearer, but the
look-up is an optimisation and the index is the guarantee. Exactly one `INSERT` can
win, whatever the timing, because the check and the write are the same operation.

**The loser resolves against the committed row, in a new transaction.** A constraint
violation aborts the whole Postgres transaction — every later statement in it fails
with "current transaction is aborted" — so the losing request cannot simply re-read
where it stands. `WithdrawalService` catches `DataIntegrityViolationException`
outside the transactional boundary and calls `WithdrawalWriter.record` a second
time. That call gets a fresh transaction, its look-up now finds the winner's row,
and it takes the replay path.

Exactly one retry, not a loop. The second attempt cannot lose the same race, because
what it collided with is now committed and visible. A violation on the second
attempt is therefore a different constraint — a foreign key, a check — and
rethrowing is the honest answer.

**Sameness is decided by a hash of the canonical request, not of the body bytes.**
`RequestHash` digests
`mini-custody:withdrawal:v1|<accountId>|<destination>|<amountWei>` after the
destination has been lower-cased. The spec says "SHA-256 of the request body", and
the literal reading is a trap: reordered JSON keys, different indentation, or an
added field the server ignores all change the bytes without changing the request,
so a client retrying through a library that re-serialises its payload would get a
`409` for doing nothing wrong. Matching is done with `MessageDigest.isEqual` rather
than `String.equals` — the timing leak is thin here, but a constant-time compare on
a money path costs nothing.

**A replay returns `202` and the original body, and does not hold funds again.** It
is the same withdrawal; there is nothing new to accept and nothing new to reserve.

## Consequences

- The happy path costs one extra `SELECT`. The contended path costs a rolled-back
  transaction and a second attempt, which is the right way round: the rare case pays.
- `saveAndFlush` rather than `save` in `WithdrawalWriter`, so the `INSERT` reaches
  Postgres inside the method. With a plain `save` the collision would surface at
  commit, after the method has returned, where nobody can still act on it. The call
  is load-bearing and is commented as such.
- Idempotency is layered rather than solved once. The API has the key, the ledger has
  `unique (kind, reference_id)`, the Kafka consumers will have `processed_events`
  (M4) and the signer will have the primary key on `signing_log` (M5). Each one
  independently makes its own step repeatable, because at-least-once delivery means
  every layer will see duplicates.
- The key is never echoed in a response or a log line. It controls a withdrawal, and
  it is arbitrary client-supplied text on its way into places where reflecting input
  causes trouble.
- The keyspace is per client, so two clients choosing the same key is not a
  collision. A global keyspace would let one client's choice deny another's.
