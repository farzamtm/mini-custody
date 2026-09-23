# 1. Pessimistic row locks for ledger balances

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M1

## Context

Two withdrawal requests arrive at the same instant against a client account holding
1 ETH. Each reads the balance, each sees 1 ETH, each concludes that 0.8 ETH is
affordable, each writes its result. The client has withdrawn 1.6 ETH from an
account that only ever held 1, and on a blockchain that money is gone. This is the
lost-update problem, and in a custody system it is the single failure mode that
costs real money rather than an apology.

Postgres does not prevent it for us. Under Read Committed — the default, and what
this project runs — each statement sees a snapshot of data committed before it
started, so both transactions genuinely do read 1 ETH. MVCC means readers never
block writers. Nothing here is a bug; the isolation level simply does not promise
what read-then-write code assumes it promises.

There are three standard answers, and this project has to pick one and be able to
defend it:

1. **Pessimistic.** Lock the rows on read with `SELECT … FOR UPDATE`. The second
   writer waits at the SELECT until the first commits, then reads the real balance.
2. **Optimistic.** Read without locking, remember a version number, and make the
   UPDATE conditional on that version still being current. Zero rows updated means
   someone else won; roll back and start over.
3. **Atomic conditional update.** Skip the read entirely:
   `update accounts set balance = balance - :amount where id = :id and balance >= :amount`.
   Zero rows updated means insufficient funds.

## Decision

**Pessimistic locking (`SELECT … FOR UPDATE`) is the default**, configured by
`ledger.locking=pessimistic` in `application.yml`.

**Optimistic locking is implemented too**, behind the same `BalanceUpdater`
interface, selected by `ledger.locking=optimistic`. It is not a sketch: the
fifty-thread concurrency test runs against both, so the comparison below is
measured rather than asserted.

**Locks are always taken in ascending account-id order.** `Posting.deltas()` sorts,
and the locking SELECT carries `order by id`. Deadlocks need a cycle — transaction
A holding X and waiting for Y while B holds Y and waits for X — and a single global
lock order makes that cycle impossible to form.

**The database backs the rule up independently.** `V2__account_balance_constraint.sql`
adds `check (type = 'EXTERNAL' or balance >= 0)`. This is not belt and braces for
its own sake: deleting the `for update` from the locking query and re-running the
concurrency test produces a `DataIntegrityViolationException` from that constraint,
not a quietly negative balance. Application logic can have bugs; the constraint is
what stops a bug from becoming a loss.

## Why pessimistic, given both work

**Contention on a balance row is high, not low.** Optimistic locking is the right
tool when conflicts are rare, because it pays nothing in the common case. A hot
client account, or later the shared `PENDING_OUT` and `EXTERNAL` accounts that
*every* withdrawal touches, is the opposite situation: conflicts are the common
case. Under the fifty-thread test, ten winners cost dozens of rolled-back
transactions, each of which did real work — inserted a journal transaction row,
read three accounts — before discovering it had lost.

**Waiting is the behaviour a balance actually wants.** A second withdrawal request
should be evaluated against the real balance a moment later. It should not be told
"try again", which is what an exhausted retry budget amounts to. Pessimistic
locking makes waiting the default and gives the caller a definite answer.

**Failure is bounded rather than probabilistic.** Optimistic locking needs a retry
limit (`ledger.max-attempts`), and any retry limit is a number that is too small
under enough load. That introduces `LedgerContentionException`, an error mode with
no business meaning that has to be mapped to a 503 and explained to a client.
Pessimistic locking has no equivalent.

**It is the honest place to fail.** With the row locked, the balance read is
authoritative, so `InsufficientFundsException` carries numbers that were true at
the moment of the decision. The optimistic path has to distinguish "you cannot
afford this" (final) from "someone moved the balance" (retry), and getting that
distinction wrong silently converts one into the other.

The cost accepted in exchange: writers block, which puts a ceiling on throughput
per account, and a transaction that holds locks and then does something slow blocks
everyone behind it. The mitigation is that `JournalWriter`'s transaction contains
nothing but SQL — no HTTP, no Kafka, no computation — so the locks are held for
microseconds.

## Rejected: the atomic conditional update

`update accounts set balance = balance - :amount where id = :id and balance >= :amount`
is simpler and faster than either, and it would be the right answer for a
single-account debit. It does not fit a double-entry ledger, where a posting moves
two or more accounts and the whole set must succeed or fail together: the second
statement failing after the first succeeded leaves the ledger unbalanced unless
there is a transaction and a lock order anyway. It is worth knowing about, and it
is what to reach for if the ledger ever needs a fast single-account path.

## Consequences

- `BalanceUpdater` has two implementations and `LedgerConfig` chooses between them.
  That is one interface more than strictly needed, paid for by being able to switch
  strategies with a property and test both.
- `LedgerService.post` retries outside the transaction, in a bean separate from
  `JournalWriter`. Under the default this loop runs exactly once. It exists because
  a rolled-back transaction cannot retry itself, and because `@Transactional` is
  proxy-based, so the retry has to cross a real bean boundary to start a new one.
- `LedgerContentionException` is dead code under the default configuration. It is
  kept because the optimistic path is real and tested.
- Lock ordering is a rule that has to be obeyed by every future caller. It is
  enforced in one place — `Posting.deltas()` — rather than left to callers, so
  there is no way to post entries in the wrong order.

## Revisit when

- A single account becomes hot enough that lock waiting shows up in latency
  percentiles. The first answer is not optimistic locking; it is splitting the
  contended account (per-client `PENDING_OUT` rather than one shared row).
- The ledger needs to run across more than one Postgres primary, at which point
  neither of these mechanisms is sufficient on its own.
