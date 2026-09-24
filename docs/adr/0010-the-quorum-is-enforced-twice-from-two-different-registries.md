# 10. The quorum is enforced twice, from two different registries

- **Status:** accepted
- **Date:** 2026-09-24
- **Milestone:** M3

## Context

M5 built a signer that re-verifies every approval before it will touch a private key,
against its own configured list of trusted public keys. M3 builds the other end: the
endpoint approvers actually call, the table their signatures are recorded in, and the
count that decides when a withdrawal has enough of them.

That immediately raises the question of what custody-api's check is *for*. The signer
does not trust it — that is the whole architecture — so a reviewer is entitled to ask
why the same rule is implemented twice, in two services, with two copies of the
threshold in two configuration files that can drift apart.

The tempting answers are both wrong. Checking only in custody-api makes the signer
trust the service the design assumes is compromised. Checking only in the signer means
a client's funds are held from the moment they request a withdrawal until a refusal
comes back over Kafka — and the refusal for "this approver is not who they say" looks
exactly like the refusal for "this approver does not exist", so nobody can fix
anything.

There is a second, less obvious question underneath it: where the list of approvers
lives. custody-api needs to add one without a deployment, because people join and
leave. The signer must not be able to, because a list a running system can edit is a
list an attacker who owns that system can edit.

## Decision

**Both services enforce the quorum, and neither is the backstop for the other.**
custody-api refuses early, with a status code and a `code` a client can act on. The
signer refuses finally, from configuration that arrived with its deployment. They are
answering different questions: "is this request well formed and authorised, as far as
this service can tell" and "may this private key be used".

**The approver registry is a table in custody-api and configuration in the signer.**
Deliberately asymmetric. The consequence is that the two lists can disagree, and the
direction matters: an approver known here and not there produces a withdrawal that is
approved and then refused — visible in the withdrawal's `failureReason`, with the hold
released. The reverse is harmless. Neither ordering can produce a signed transaction
that nobody approved, which is the only property worth protecting.

**What is signed is an `ApprovalStatement` rebuilt from stored state, never from the
request.** custody-api builds it from the withdrawal row; the signer builds it from the
event's own fields. A caller therefore cannot present a signature over terms of their
own choosing, and an attacker who edits a destination on the wire invalidates every
signature over it. The statement is three fields — withdrawal, destination, amount —
because anything else in a signature's scope is something an approver would be
endorsing without having been shown it.

**Quorum counts approvers, not approvals.** Enforced here by the
`(withdrawal_id, approver_id)` primary key and in the signer by a set of ids. One
person sending their valid signature twice is a copy and a paste away, and it defeats
four eyes entirely.

**Self-approval is defined by `approvers.client_id`.** An approver registered against a
client may not approve that client's withdrawals; custodian staff have no client and may
approve anything. This is the honest version of the rule available without
authentication — there is nothing that identifies who *requested* a withdrawal, so the
control is expressed against the party whose money it is rather than against the person
who typed the request.

**The withdrawal row is locked with `SELECT … FOR UPDATE` for the length of an
approval.** Rejected alternative: rely on the `@Version` column, as the approval path
did in M4. It is sound but it resolves the race by rolling a transaction back, and the
thing being rolled back is a signature somebody meant to give — the approver is asked to
sign again for a reason that is not their problem. Without any serialisation the failure
is worse and quieter: two concurrent approvals each see only their own, both conclude
the quorum is short, and a fully approved withdrawal waits forever for a third signature
nobody will send. `ApprovalConcurrencyTest` fails on exactly that if the lock is removed.

**`Ed25519` moved from the signer into `common`.** Both services now verify the same
signatures over the same statement, and two copies of the raw-key unpacking are two
chances to get the byte order wrong. The failure mode of a disagreement is not a crash:
custody-api would accept approvals the signer rejects, the withdrawal would stall, and
both services would be certain they were right. ADR 0009 accepts duplication for the
outbox because that code is Spring infrastructure and `common` must stay
framework-free; this is fifty lines of pure JDK with no such obstacle.

## Consequences

- **Two thresholds to keep in step**, in `approvals.second-approval-from-wei` and
  `signer.policy.second-approval-from-wei`. There is no mechanism keeping them equal and
  there deliberately is not, because the mechanism would be a shared source of truth that
  the signer reads — which is the thing being avoided. The stricter of the two wins,
  since both have to pass.
- **A `POST /dev/approvers` endpoint exists**, so the README walk-through can generate a
  key pair and use it. It is `@Profile("dev")`, so the bean is not created otherwise.
  What bounds it even if that were wrong is that it cannot reach the signer's trusted
  list — the same argument that makes the dev approval endpoint survivable.
- **The approvals endpoint is write-only.** There is no `GET .../approvals`: who has
  approved a payment is not something an unauthenticated API should read out. The `409`
  for a duplicate approval does disclose that *this* approver has already approved, which
  is why it is checked after the signature — every fact beyond "unknown approver" costs a
  valid signature to learn.
- **Signatures are stored, not just checked.** The row is the evidence, so the decision
  can be re-verified later by anyone, including the signer. An audit trail that records a
  verdict rather than the thing the verdict was about has to be trusted.
- **An approval cannot be withdrawn.** There is no path that updates or deletes a row in
  `approvals`, for the same reason the ledger is append-only. Changing one's mind before
  the quorum completes is not modelled, and would want a rejection event of its own
  rather than a delete.
- **Nothing ties the two services' agreement down in a test that runs both.** They are
  separate Gradle modules with separate Spring contexts. What is tested instead is that
  they agree on the bytes: `ApprovalEventFlowIntegrationTest` re-verifies each published
  approval the way `SigningPolicy` will, against a statement rebuilt from the event after
  a round trip through `jsonb` and Kafka. That covers the failure that would actually
  happen; it does not cover the trust decision, which is `SigningPolicyTest`'s.
