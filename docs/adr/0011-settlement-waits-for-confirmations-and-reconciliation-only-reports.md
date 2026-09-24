# 11. Settlement waits for confirmations, and reconciliation only reports

- **Status:** accepted
- **Date:** 2026-09-24
- **Milestone:** M6

## Context

M5 left every withdrawal in `BROADCAST`. The signer had put a transaction on the wire
and told custody-api its hash, and there the story stopped: the client's funds were
still held in `PENDING_OUT`, `EXTERNAL` had not moved, and nothing in the system had
any opinion about whether the money had actually gone.

Finishing it means answering a question nothing else in this project has had to ask.
Every other input is something this system produced — a client's request, an
approver's signature, the signer's own report. This one comes from a chain that is
not authoritative in the way a database is: a transaction can sit in a mempool for an
hour, be dropped, be mined into a block that is later reorganised away, or be mined
and revert. "The transaction exists" and "the money left" are different claims, and
only the second is the one the ledger is about.

There is a second question underneath it. A watcher that settles on what the chain
said at one moment cannot notice if the chain later says something else — it has
finished with that withdrawal and will never look again, and no event is emitted when
a block quietly stops existing. So a ledger that has settled something that did not
happen is a ledger that is perfectly self-consistent and wrong, indefinitely.

## Decision

**Settlement waits for `chain.confirmations` blocks on top of the receipt, and the
count is inclusive.** A transaction in the head block has one confirmation, not zero.
Both readings are defensible; only one matches every block explorer, and the other
settles a block early — on the block most likely to be reorganised away.

**Three is a number for a demo, and the honest alternative is named.** A real
deployment would use Ethereum's `finalized` block tag, which is an actual consensus
guarantee rather than a guess about how deep a reorg can go. Three exists so the
local walk-through finishes while somebody is watching.

**A mined transaction is not a successful one.** The receipt's `status` decides:
`0x1` settles, `0x0` fails the withdrawal and releases the hold. Settling on the
existence of a receipt would credit `EXTERNAL` for money that never left. The check is
written as "is it success" rather than "is it failure", so a node returning something
unexpected reads as not-successful, which is the safe direction.

**The fee is booked for a revert as well as for a success**, because the chain charges
for both. A fee only recorded on the happy path is a ledger that drifts from the hot
wallet by exactly the amount of every failure — a discrepancy that takes a week to
find and looks like a rounding bug until it does not.

**Gas comes out of `BANK_OPERATING`, which V5 seeds with a float.** A withdrawal of
0.4 ETH that cost the client more than 0.4 ETH is not what the client was told. The
float is booked as a real journal transaction rather than by setting a balance column,
because `accounts.balance` is a cache of the journal and a migration that wrote one
without the other would make `recomputedBalanceOf` disagree with `balanceOf` on the
first assertion anybody made about it.

**The batch is claimed with `FOR UPDATE SKIP LOCKED`, and the RPC calls happen inside
the transaction.** Both decisions are the outbox relay's, made again for the same
reasons (ADR 0005): `SKIP LOCKED` is what makes a second instance take a disjoint
batch rather than block and re-process, and doing the reads outside the transaction
would shorten the lock at the cost of a window in which the status changed underneath
the answer. `eth_blockNumber` is fetched once per batch, so two transactions mined in
the same block are counted against the same head.

**An unreachable node settles nothing.** `RpcException` propagates, the transaction
rolls back, the locks go, and the next tick tries again. This is the property the
whole design turns on: a node that cannot be asked has said nothing, and "no answer"
must never reach the ledger as "no receipt".

**The observed receipt is stored, and it is a cache rather than a record.** It is
overwritten on every tick, which is a deliberate departure from the ledger's
append-only rule: this is what the chain currently says, and the chain is allowed to
change its mind. What is append-only is the settlement that results. Storing it at all
buys three things — evidence separate from the decision, a confirmation count the API
can serve without an RPC call per request, and the ability to notice a reorg at all,
since a watcher with no memory cannot tell "mined and then un-mined" from "never
mined".

**Reconciliation reads and reports; it does not repair.** Every finding has more than
one possible cause — a settlement with no receipt behind it might want a reversing
entry, or might mean the node being asked is on the wrong chain — and the right remedy
depends on which. A job that guessed would turn a detectable problem into two. The
ledger is append-only, so the repair is a reversing entry made by somebody who has
looked.

**Reconciliation asks the chain, not the receipt table.** Comparing a stored receipt
against a stored settlement is comparing this service to itself, which proves only that
it is consistent. A service that has settled something that never happened is
consistent about it.

**The hot wallet's balance is reported and never asserted on.** It cannot be
reconciled in this system: deposits are fabricated by `POST /dev/deposits` rather than
observed on chain, because nothing here watches for incoming transfers. A check that
failed on every run would train everybody to ignore the report.

**custody-api gets its own JSON-RPC client rather than sharing the signer's.** Three
read-only calls against a hand-rolled client, and deliberately without web3j: the
signer carries `org.web3j:crypto` because it does secp256k1 and RLP, and putting an
Ethereum crypto library on custody-api's classpath would quietly weaken the claim that
the signer is the only component that can sign. The module that could hold a shared
client is `common`, which must stay framework-free and would need Spring's
`RestClient`. This is the same judgement ADR 0009 made about the outbox, with one
addition: the two clients have different failure policies, and the difference is
load-bearing — the signer treats "already known" as success, and nothing here should.

## Consequences

- **A withdrawal can sit in `BROADCAST` for ever.** If the transaction is underpriced,
  the signer resends the same bytes on its own timer and they stay unmined, because
  nothing here reprices. Reconciliation reports it as `BROADCAST_BUT_NOT_MINED` after
  `chain.stuck-after`; acting on it is a person's job. Automating it needs replacing
  the transaction at the same nonce with a fee about 10% higher, which is a change to
  the signer.
- **A reorg shallower than three blocks is handled and a deeper one is not.** Within
  the threshold the watcher forgets the receipt and waits for the signer's resend.
  Beyond it, the ledger has settled and only reconciliation will say so. That is what
  choosing a fixed confirmation count means, and it is the reason `finalized` is the
  right answer in production.
- **`transaction_receipts.confirmations` is denormalised and stops moving at
  settlement.** It is derivable from `block_number` and the head, and fetching the head
  per request is exactly the cost it avoids on an endpoint clients poll. Once the
  withdrawal is `CONFIRMED` the watcher no longer looks at it, so the number means "how
  deep it was when we settled" — which is the number worth keeping anyway.
- **The operating float is invented by a migration.** In production it arrives when the
  treasury funds the hot wallet, booked by whatever observes that happening — the same
  deposit watcher this project does not have. Ten ETH is effectively unlimited at
  Anvil's gas prices.
- **`GET /v1/reconciliation` makes one JSON-RPC call per settled or in-flight
  withdrawal.** At real volume that is a scheduled job writing to a report, not a
  request anybody waits on. It is an endpoint here because being able to ask the
  question by hand is what makes the property demonstrable rather than asserted.
- **A package cycle was avoided with a one-method interface.** `ConfirmationCount` is
  declared in the withdrawal package and implemented in the confirmation package, which
  already depends on it. There is one implementation and no expectation of a second;
  the interface is about direction, not substitutability.
