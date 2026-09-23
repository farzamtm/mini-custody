package com.farzam.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * "This withdrawal has its quorum. Sign it."
 *
 * <p>Published by custody-api on {@code custody.withdrawals.v1}, consumed by the signer.
 *
 * <p><b>Why the approvals travel with the event.</b> The signer has its own database and cannot read
 * custody's, so the evidence has to come to it. But it does not <em>trust</em> what arrives: it
 * re-verifies each signature, and checks each approver against its own list of trusted public keys
 * rather than the keys in this list. That is the whole security argument of the split — an attacker
 * who owns custody-api can put any list they like in here, and the signer will still refuse, because
 * forging a signature needs a private key custody-api has never held.
 *
 * <p>The list is therefore a claim, not a credential. {@code publicKey} is present so the signer can
 * tell which of its own trusted keys a signature claims to be from, not so that it can be used as
 * the key to verify with.
 *
 * <p><b>What the signature covers is not this record.</b> An approver signs the facts they are
 * approving — the withdrawal id, the destination and the amount — and cannot sign a payload that
 * contains their own signature. M3 defines that statement and its canonical form; the three fields
 * above are repeated here so the signer has them without a round trip, and so that a tampered
 * destination or amount fails verification rather than being quietly signed.
 *
 * @param withdrawalId which withdrawal — the same value as the envelope's aggregate id
 * @param destination the address the funds go to, lower-case hex
 * @param amountWei how much, in wei
 * @param approvals the approvals collected, as evidence to be re-checked
 */
public record WithdrawalApproved(UUID withdrawalId, String destination,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigInteger amountWei, List<Approval> approvals) {

    /**
     * @throws NullPointerException if any field is missing
     */
    public WithdrawalApproved {
        Objects.requireNonNull(withdrawalId, "withdrawalId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amountWei, "amountWei");
        // List.copyOf, not the caller's list. A record is only as immutable as its components, and
        // this one is serialised, published and then verified against — a caller holding on to the
        // original list could change the evidence between building the event and sending it.
        // It also rejects a null list and null elements, which is the other half of the check.
        approvals = List.copyOf(approvals);
    }

    /**
     * One approver's sign-off, as it crosses the wire.
     *
     * <p>Base64 rather than hex for the two byte fields, and the raw 32/64 bytes rather than any
     * container format: Ed25519 keys and signatures are fixed-length byte strings, and every
     * encoding choice here is one both services have to agree on exactly, because the bytes are what
     * gets verified.
     *
     * @param approverId who
     * @param publicKey their Ed25519 public key, 32 bytes, base64
     * @param signature their signature over the canonical payload, 64 bytes, base64
     */
    public record Approval(UUID approverId, String publicKey, String signature) {

        /**
         * @throws NullPointerException if any field is missing
         */
        public Approval {
            Objects.requireNonNull(approverId, "approverId");
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(signature, "signature");
        }
    }
}
