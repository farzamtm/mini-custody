package com.farzam.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * Exactly what an approver puts their name to.
 *
 * <p>An Ed25519 signature is over bytes, and the two sides of it here are written months apart by
 * different services: custody-api's approval endpoint produces the signature (M3), the signer
 * verifies it before it will touch a private key (M5). Both have to derive the same bytes from the
 * same facts, so the statement is a shared record rather than a string each side assembles — a
 * verifier that concatenated the fields in a different order would reject every honest approval, and
 * would say nothing more useful than "signature invalid" while doing it.
 *
 * <p><b>Three fields, and no more.</b> This is the whole of what an approver is agreeing to: this
 * withdrawal, to this address, for this amount. Anything else in a signature's scope is something an
 * approver would be endorsing without having been shown it. The corollary matters as much — a
 * destination or an amount that changes after approval invalidates every signature over it, which is
 * the property that makes a tampered {@link WithdrawalApproved} fail at the signer rather than
 * getting signed.
 *
 * <p><b>Not the event, and deliberately not part of it.</b> {@link WithdrawalApproved} carries these
 * three fields <em>and</em> the list of approvals; an approver cannot sign a document that contains
 * their own signature. The signer reconstructs this statement from the event's fields and checks
 * each approval against it.
 *
 * <p>The bytes are {@link EventJson#canonicalBytes}: sorted keys, no whitespace, amounts as decimal
 * strings. {@code EventJson} explains why the canonical form has to be recomputed on both sides
 * rather than carried along with the signature.
 *
 * @param withdrawalId which withdrawal
 * @param destination where the funds go, lower-case hex
 * @param amountWei how much, in wei
 */
public record ApprovalStatement(UUID withdrawalId, String destination,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigInteger amountWei) {

    /**
     * @throws NullPointerException if any field is missing
     */
    public ApprovalStatement {
        Objects.requireNonNull(withdrawalId, "withdrawalId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amountWei, "amountWei");
    }

    /**
     * The statement the approvals on an event were supposed to be over.
     *
     * <p>Built from the event rather than from anything the signer holds, so that a forged event is
     * checked against what it actually claims. That sounds backwards and is not: the signature has to
     * verify against the event's own destination and amount, so changing either of them breaks it.
     * An attacker who edits the destination and leaves the signatures alone produces a statement
     * nobody signed.
     *
     * @param event the approval event as it arrived
     * @return the statement each of its approvals is verified against
     */
    public static ApprovalStatement of(WithdrawalApproved event) {
        return new ApprovalStatement(event.withdrawalId(), event.destination(), event.amountWei());
    }

    /**
     * The bytes to verify a signature against.
     *
     * @return this statement in its one canonical encoding
     */
    public byte[] canonicalBytes() {
        return EventJson.canonicalBytes(this);
    }
}
