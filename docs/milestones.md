# Milestones and gaps

Where this project got to, and where it deliberately stops. The [README](../README.md)
is the short version of what the system does; [design.md](design.md) is the argument for
how it does it.

## Milestones

All eight have landed.

| | Milestone | What it demonstrates |
| --- | --- | --- |
| ✅ | **M0** Skeleton | Multi-module Gradle, Docker stack, Flyway-owned schema, Testcontainers |
| ✅ | **M1** Ledger | Double-entry posting, row locking, concurrency under 50 threads |
| ✅ | **M2** Withdrawal API | API-first OpenAPI contract, idempotency keys, RFC 9457 errors |
| ✅ | **M3** Approvals | Ed25519 four-eyes approval with amount-based quorum |
| ✅ | **M4** Outbox and Kafka | Transactional outbox, `SKIP LOCKED` relay, idempotent consumers, DLT |
| ✅ | **M5** Signer | Envelope encryption, nonce management, EIP-1559 signing and broadcast |
| ✅ | **M6** Confirmations | Receipt polling, settlement, reconciliation against the chain |
| ✅ | **M7** Signed results | The results topic authenticated, closing a forged-refusal double-spend |

**They were not built in that order, and the detour is the interesting part.** M4 and M5
came before M3, so the signer existed for a while with nothing able to produce an
approval it would accept. That was the right way round: it meant the approval machinery
had to satisfy a verifier that already existed and had not been written to accommodate
it. Building them in numerical order would have let both halves drift towards each other.

M7 came from a review of this repository that found an exploitable double-spend in its
own design — `custody-api` believed anything on the signer's results topic, including
"the signer refused, give the money back", which releases a ledger hold. An integration
test already in the repository *was* the exploit, written months earlier as a happy-path
assertion.
[ADR 0012](adr/0012-signer-results-are-authenticated-the-way-approvals-are.md) is
the write-up.

## What a production system would do differently

This is a learning project, and the gaps are deliberate rather than overlooked:

- **Keys.** They would live in an HSM or be split with MPC, and the private key would
  never exist in the signing process. Here a master key comes from an environment
  variable standing in for a KMS, so a heap dump of the signer is a total loss — the
  single largest gap in this project.
- **One hot wallet**, no warm or cold tier, no HD derivation. A real custodian keeps
  most assets offline and derives a fresh deposit address per client.
- **Stuck transactions are resent, never repriced.** A fee that is too low needs
  replacing at the *same* nonce about 10% higher, and nothing here does that.
  Reconciliation reports it as `BROADCAST_BUT_NOT_MINED`; acting on it is a person's job.
- **Nothing watches for incoming deposits.** `POST /dev/deposits` fabricates them, so
  the ledger's view of holdings cannot be reconciled against the hot wallet's on-chain
  balance. A deposit watcher is the obvious next milestone.
- **Finality** would use Ethereum's `finalized` block tag, not a fixed 3 confirmations —
  that number exists to keep a local demo fast.
- **No authentication**, one chain, one asset. That gap shapes the approvals design
  rather than sitting beside it: nothing identifies who *requested* a withdrawal, so
  self-approval is defined against the client whose money it is. With a credential on
  the request the rule would tighten, and the registry already has the column.
- **Single Kafka broker**, no replication, plain-text passwords in `docker-compose.yml`.
  Local development configuration, not deployable.
- **The outbox relay stops its batch on a failed send**, so one unsendable row halts it.
  A production relay would count attempts, park the row, and alert on the age of the
  oldest unpublished one.
- **Nothing consumes either dead-letter topic.** That matters most on the signer's side,
  where a briefly unreachable Ethereum node can strand a withdrawal for good. A
  production system would redrive the topic and give a transient `RpcException` a far
  longer budget than a malformed event deserves.
- **Both services carry their own outbox writer and relay.**
  [ADR 0009](adr/0009-the-outbox-is-duplicated-rather-than-extracted-for-now.md) is
  the deferral and what would make it worth revisiting; M7 made the two copies genuinely
  different for the first time, which reprices it.
- **`processed_events` grows for ever**, in both services. It needs a job deleting rows
  older than the topic's retention.
