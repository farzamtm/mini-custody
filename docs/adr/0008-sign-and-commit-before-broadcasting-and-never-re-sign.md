# 0008 — Sign and commit before broadcasting, and never re-sign

Status: accepted (M5)

## Context

Signing a withdrawal and putting it on the network are two operations against two
systems with no transaction between them. That is the dual-write problem again —
the one the outbox solved for Postgres and Kafka in ADR 0005 — except that here
the second system is a public blockchain and the failure mode is a payment rather
than a message.

There are only two orders, and they are not symmetrical:

**Broadcast, then commit.** A crash in between puts money on the chain with no
record in this service that a transaction was ever signed, let alone sent. No
nonce recorded, no raw transaction, no hash. The next approval for the same
withdrawal signs again with the next nonce and pays the same person twice, and
the only way to find out is to reconcile against the chain.

**Commit, then broadcast.** A crash in between leaves a row in `signing_log` with
a null `broadcast_at` and nothing on the network. Nobody is paid, and everything
needed to finish the job is on disk.

The second failure is recoverable and the first is not. That settles the order
and leaves the harder question: what does recovery do?

## Decision

**One transaction covers the nonce reservation, the signature, the signing log
and the outbox row. The broadcast happens after it commits.**

The listener does the first four inside `@Transactional`; the send is triggered by
a Spring `@TransactionalEventListener(AFTER_COMMIT)`. The transaction hash is
known before anything is sent, because it is keccak-256 of the signed bytes — so
the `WithdrawalBroadcast` event can be written in the same transaction as the
signature, and custody-api gets the identifier it will follow the payment by
whether or not the first send attempt works.

**Recovery resends the identical bytes. It never signs again.**

This is the load-bearing half. A transaction's hash is a hash of its bytes, so
the network accepts the same signed transaction exactly once however many times
it is offered: a second node that already has it says so and nothing happens.
Re-signing is a different matter entirely — it takes a new nonce and produces a
genuinely different transaction, and if the first one was merely slow rather than
lost, both can be mined and the client is paid twice. `signing_log` keeps
`raw_tx` for exactly this reason, and `withdrawal_id` is its primary key so that a
second signature cannot be recorded even if something tried.

`Broadcaster.resendBacklog()` picks up rows with a null `broadcast_at` older than
ten seconds, lowest nonce first, and sends them again.

## Consequences

**`WithdrawalBroadcast` can be published before the bytes are on the wire.** The
event is committed with the signature; the send may still be pending or failing.
custody-api will move the withdrawal to `BROADCAST` slightly early. This is the
right way round — the alternative is a withdrawal stuck in `APPROVED` with the
client's funds held because a node was briefly unreachable — and it is honest
about what `BROADCAST` means in this system: signed, final, and committed to
being sent. Nothing is settled in the ledger until M6 sees three confirmations.

**A gap in the nonce sequence blocks the wallet.** A transaction that is signed
and then never successfully sent leaves a nonce that nothing will use, and nonce
*n+1* cannot be mined until *n* is. The retry job makes that rare rather than
impossible. The manual remedy is the standard one — sign a zero-value transaction
to yourself at the stuck nonce — and it is not automated here.

This is not theoretical: the first version of `BroadcastRetryTest` signed from the
shared hot wallet and deliberately did not send, and every other test in the
module started timing out because their transactions were queued behind the gap.
The tests now sign from their own wallets.

**"Already known" and "nonce too low" mean different things and read the same.**
A node refusing a resend is saying the nonce is spent, and that is either this
transaction having been mined — fine — or something else having spent it, in
which case this transaction can never be mined and needs a human. The error text
cannot tell them apart, so `Broadcaster` asks for a receipt on the hash: a
receipt means the resend was redundant and the row is marked broadcast; no
receipt means it stays queued and warns.

**The retry job resends; it never reprices.** A transaction whose fee was too low
sits in the mempool indefinitely, and the fix is a replacement carrying the *same*
nonce and a fee at least ~10% higher, so that at most one of the two can be mined.
That is a signing decision about real money, it needs a view of how long is too
long, and it belongs with the watcher that is actually watching — M6.

**A production relay would park a row that has failed enough times.** As written,
one permanently unsendable transaction stops the batch, because the ordering by
nonce only means something if it is respected. The metric that matters is the age
of the oldest unbroadcast row, and alerting on it is what a real deployment would
add.

## Alternatives rejected

**Broadcast inside the transaction and roll back if it fails.** Attractive
because it looks atomic, and it is the worst option available: the network has no
rollback, so a successful send followed by a failed commit is a payment with no
record of it.

**Publish `WithdrawalBroadcast` only after a confirmed send.** Removes the
early-`BROADCAST` wrinkle above, and needs its own outbox write in a second
transaction after the send — which reintroduces the crash window it was meant to
close, in the place where losing the event leaves funds held indefinitely.

**Track the sent state on the chain rather than in `signing_log`.** Ask the node
whether the transaction exists instead of keeping `broadcast_at`. It is one fewer
column and it makes recovery depend on a node being reachable to discover that
there is work to do, at exactly the moment the node is likely to be the problem.
