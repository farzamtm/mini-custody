# 12. Signer results are authenticated, the way approvals already were

Date: 2026-09-25

## Status

Accepted.

## Context

The security argument this system is built around is that owning `custody-api` is not
enough to move funds. The signer has no HTTP endpoint, reacts only to Kafka events, and
before it will touch a private key it re-verifies every approval signature against its
*own* list of trusted public keys rather than the list carried in the event
([ADR 0010](0010-the-quorum-is-enforced-twice-from-two-different-registries.md)). An
attacker who completely owns `custody-api` can mark a withdrawal approved in the database
and publish whatever they like on `custody.withdrawals.v1`, and the signer still refuses.

That distrust ran in one direction only.

`custody-api` consumed `signer.results.v1` and believed it. The envelope carried no
signature, `KafkaConfig` configured plain string values with no authenticating
deserialiser, and `SigningResultListener` dispatched on `eventType` after nothing more
than a duplicate check against an attacker-chosen `eventId`. One of the messages it
accepted was `WithdrawalSigningFailed`, which calls `releaseAfterFailure` — and that posts
`WITHDRAWAL_RELEASE`, crediting the full amount back to the client's account.

So anyone able to produce to `signer.results.v1` could:

1. Read a withdrawal id off `custody.withdrawals.v1`, where it is the envelope's
   `aggregateId`.
2. Publish a `WithdrawalSigningFailed` naming it, with a fresh `eventId`.
3. Have `custody-api` release the hold and restore the client's balance.

Meanwhile the real signer, which never saw that message, went on to sign and broadcast the
transaction. The ETH left the hot wallet and the ledger said it had not. The genuine
`WithdrawalBroadcast` that followed hit a withdrawal now in `FAILED`, failed the state
transition, and dead-lettered — so the only trace was a record on a topic nothing reads.
`unique (kind, reference_id)` caps the loss at one release per withdrawal, but the attack
is repeatable across every withdrawal in `APPROVED` or `BROADCAST`.

The uncomfortable part is that the repository already contained a working demonstration.
`WithdrawalEventFlowIntegrationTest.aRefusalFailsTheWithdrawalAndGivesTheMoneyBack` built
an envelope with `UUID.randomUUID()`, published it straight to the topic with no signer
involved, and asserted the money came back. The only difference between that test and an
attacker was who held the producer.

This is not the accepted "single Kafka broker, plain-text passwords in
`docker-compose.yml`" gap in the README. That one is about broker hardening and local
development credentials. This is the absence of any application-level control at all, and
the codebase's own `SigningPolicy` javadoc already declares a hostile topic producer to be
in scope — it defends against exactly that adversary in the other direction.

## Decision

The signer signs every message its outbox relay publishes, and `custody-api` refuses any
result it cannot verify.

**Ed25519, reusing `common`.** Both services already share `Ed25519` and `EventJson`.
`Ed25519` gained `sign` and `privateKeyFrom`; the new `EventSignature` holds the header
name and the sign/verify pair. Nothing new was invented and no second crypto library
arrived.

**The signature covers the serialised record value, not a re-derived canonical form.**
Kafka delivers a record's value byte for byte, so the producer signs exactly what it sends
and the consumer verifies exactly what it received. This is deliberately unlike
`ApprovalStatement`, which *must* be rebuilt from stored fields because `jsonb` does not
preserve the bytes that went into it. Here nothing is stored between signing and
verifying, so the stricter option is available and there is no canonicalisation step that
could disagree.

**It travels in a Kafka header, not a field on the envelope.** A signature over a
structure cannot live inside that structure. Putting it on `EventEnvelope` would mean
signing some subset of the envelope's own fields and then arguing about which — and every
such argument is a place for a field to be quietly left out of the signed set. A header
covers the whole value and leaves the event contract both services parse unchanged.

**The check runs before the parse and before `processed_events`.** Ahead of the parse
because an unauthentic message should cost as little as possible. Ahead of the duplicate
check because `markProcessed` is a write, and letting an attacker insert rows keyed on ids
they choose is a small denial-of-service against the one table that makes redelivery safe.

**Keys are deployment configuration on both sides**, like `signer.policy.trusted-approvers`
and for the same reason: a key a running system can edit is a key an attacker can edit.
The signer reads `signer.results.signing-key` (a base64 32-byte seed); `custody-api` reads
`custody.signer-results.public-key`.

**A key separate from the wallet key and the master key.** This one cannot move funds, so
it rotates on its own schedule and a leak of it is an authenticity problem rather than a
custody one. It is also a different algorithm from the wallet's secp256k1, which keeps the
two impossible to confuse in configuration.

**The two sides fail closed in different directions, on purpose.** An absent public key on
`custody-api` means every result is refused — noisy and safe. An absent signing key on the
signer is a *startup failure*, because a signer that signs transactions but cannot
authenticate its own reports is worse than one that does nothing: it would broadcast on
chain and then have every report rejected, stranding each withdrawal with the client's
funds held and the money already gone.

**`UnauthenticEventException` is non-retryable.** A signature that does not verify now will
not verify in two seconds, and the one way to produce these messages in volume is
deliberately. Retrying each three times would let whoever is producing them cost the
service four times the work and block the partition for legitimate results queued behind.
It is a distinct type from `EventFormatException` so the dead-letter topic can tell "the
bytes are not an event" from "nobody entitled to send this sent it" — only one of those is
worth waking a person for.

## Consequences

The trust model is now symmetric. Compromising either service yields the other's keys in
neither direction: the signer will not sign without approver signatures it can verify, and
`custody-api` will not move a ledger hold without a signer signature it can verify.

The two outbox relays have diverged further. ADR 0009 deferred extracting them into a
shared module; the signer's copy now signs and `custody-api`'s does not, so a future
extraction has one more difference to reconcile. This is the right side of the trade —
`custody-api` publishes to a topic the signer already treats as hostile, so signing there
would buy nothing today — but it makes the deferral more expensive and is worth saying out
loud.

Every deployment now needs a key pair, and a deployment that forgets the public key has
every signer result dead-lettered: withdrawals reach `APPROVED`, get signed and broadcast,
and then never progress. That is a bad failure. It is the *noisy* bad failure, and the
alternative default — accepting unauthenticated instructions to release holds — is worse by
a different order of magnitude and fails silently in the attacker's favour.

The `reason` field on a refusal is now attacker-controlled only by the signer, which
slightly strengthens the existing decision never to log it.

## Alternatives rejected

**Kafka ACLs and mTLS, with no application-level check.** The correct production answer for
*who may produce to the topic*, and it is complementary rather than sufficient: it puts the
entire security property in broker configuration, where this repository cannot test it and
a reviewer cannot see it. The signer already decided this argument in the other direction
by re-verifying approvals rather than trusting that only `custody-api` can publish.

**Signing the payload rather than the envelope.** Cheaper, and it leaves `eventId`,
`eventType` and `aggregateId` unsigned — so a genuine broadcast result could be replayed as
a refusal, or pointed at a different withdrawal. There is a test for exactly this
(`aGenuineSignatureCannotBeReplayedOntoADifferentResult`).

**A shared secret and an HMAC.** Symmetric, so `custody-api` would hold a key capable of
*producing* valid results. Compromising `custody-api` would then be enough to forge them,
which is the scenario the whole design exists to survive.

**Making the state machine reject `APPROVED -> FAILED` unless a broadcast was seen.** Would
narrow this particular exploit without addressing the cause, and would break the legitimate
case the transition exists for: the signer genuinely refusing a withdrawal it will not
sign. Defence in depth worth considering separately, not a substitute.
