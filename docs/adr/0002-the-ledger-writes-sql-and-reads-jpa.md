# 2. The ledger writes through SQL and reads through JPA

- **Status:** accepted
- **Date:** 2026-09-23
- **Milestone:** M1

## Context

The project uses Spring Data JPA, and the obvious thing is to use it for
everything: load an `Account`, change its balance, let Hibernate's dirty checking
write it out at commit. That works, and for most entities it is the right amount
of machinery.

It is the wrong amount for the ledger. The three statements that make the ledger
correct are all statements whose exact text matters:

- `select … for update` — the lock, and its `order by id`
- `insert … on conflict (kind, reference_id) do nothing` — the idempotency check
- `update … where id = :id and version = :version` — the optimistic compare-and-set

Each of these is the decision, not an implementation detail of the decision. Behind
JPA they become `@Lock(LockModeType.PESSIMISTIC_WRITE)`, a try/catch around a
`DataIntegrityViolationException`, and a `@Version` field whose UPDATE Hibernate
writes for you. The behaviour is similar. What is lost is that a reader of the code
can no longer see what SQL runs, which for the parts of a custody system that hold
money back from being spent twice is the thing most worth being able to see.

There is also a mechanical problem. Mixing the two in one transaction means
Hibernate's persistence context can hold an `Account` whose balance a sibling SQL
`UPDATE` has already changed, and the stale copy wins at flush time. Avoiding that
requires `flush()`/`clear()` calls placed by hand, which is more subtle than either
approach on its own.

## Decision

**The write path is `JdbcClient` only.** `JournalWriter`, `PessimisticBalanceUpdater`
and `OptimisticBalanceUpdater` load no entities. Nothing enters the persistence
context during a posting, so there is no stale copy to go wrong.

**The read path is JPA.** `Account`, `JournalTransaction` and `JournalEntry` are
`@Entity` classes with Spring Data repositories, used for balance lookups, the
journal, and — from M2 — serving the REST API.

**The entities are still mapped even though nothing writes them through Hibernate**,
because `spring.jpa.hibernate.ddl-auto: validate` compares them against what Flyway
built at every startup. An entity that drifts from its migration fails the boot
rather than the request, and `CustodyApiApplicationTests` catches it in CI.

**Everything else in the project keeps using JPA normally.** This decision is scoped
to the ledger; withdrawals and approvals in M2 and M3 have no SQL worth staring at
and should not pay for this.

## Consequences

- `accounts.version` is maintained by two different mechanisms: Hibernate's
  `@Version` for entity writes, and `version = version + 1` written out by hand in
  the ledger's SQL. They agree, but the duplication is real and is called out in the
  `Account` javadoc.
- Column names appear as string literals in SQL rather than being derived from the
  mapping, so renaming a column means changing both. The Flyway migration is the
  single source of truth and both sides are checked against it — the entity by
  `ddl-auto: validate`, the SQL by the tests failing.
- `BigInteger` round-trips through `BigDecimal`: `rs.getBigDecimal("balance")
  .toBigIntegerExact()`. `toBigIntegerExact` rather than `toBigInteger` on purpose —
  the column is `numeric(78,0)` and can have no fractional part, so if one ever
  appears the right response is an exception, not silent truncation of somebody's
  money.
- Reading the ledger's concurrency control means reading SQL, not inferring it from
  annotations. That is the point.
