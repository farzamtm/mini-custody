package com.farzam.custody.approval;

import com.farzam.crypto.Ed25519;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.security.PublicKey;
import java.util.Objects;
import java.util.UUID;

/**
 * Somebody whose signature counts towards a quorum.
 *
 * <p><b>A table here, configuration in the signer, and that asymmetry is deliberate.</b> custody-api
 * has to be able to add an approver without a deployment — people join and leave — so the registry
 * is data. The signer must not, because an approver list a running system can edit is an approver
 * list an attacker who owns that system can edit, and the signer's whole job is to be the component
 * that a compromised custody-api cannot talk into anything. So the signer's copy arrives with the
 * deployment and this one does not, and the signer never reads this table.
 *
 * <p>The consequence is that the two lists can disagree, and the direction they disagree in decides
 * what happens. An approver here but not in the signer's configuration produces a withdrawal that
 * custody-api approves and the signer refuses — visible, recoverable, and the hold goes back. The
 * reverse is harmless. Neither can produce a signed transaction nobody approved, which is the
 * property worth keeping.
 *
 * <p>{@code public_key} is the raw 32 bytes, not a PEM or an X.509 wrapper: it is what
 * {@link Ed25519} takes, what the signer's configuration holds, and what travels on an approval. One
 * encoding, verified the same way on both sides.
 *
 * <p>{@code final} for the reason given on {@link com.farzam.custody.ledger.Account}: the
 * constructor validates, and SpotBugs is right that a subclass could catch the throw and keep a
 * half-built object.
 */
@Entity
@Table(name = "approvers")
public final class Approver {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /**
     * The raw 32-byte Ed25519 public key.
     *
     * <p>Stored as {@code bytea} rather than base64 text. The bytes are what gets verified, and a
     * column that holds an encoding of them is a column somebody can put a differently-encoded copy
     * of the same key into — at which point two rows that look different describe the same signer.
     */
    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    /**
     * Who this approver acts for, or null for custodian staff.
     *
     * <p>Null is the ordinary case and means independent of every client. A non-null value is what
     * {@link ApprovalService} checks self-approval against: this approver may not approve a
     * withdrawal belonging to this client.
     */
    @Column(name = "client_id")
    private UUID clientId;

    /** JPA requires a no-arg constructor; it is not part of the API. */
    Approver() {}

    /**
     * Registers an approver.
     *
     * @param name who they are, for a human reading an audit trail
     * @param publicKey their raw 32-byte Ed25519 public key
     * @param clientId the client they act for, or null for custodian staff
     * @return the new approver, not yet saved
     * @throws IllegalArgumentException if the key is not a well-formed Ed25519 public key
     */
    public static Approver register(String name, byte[] publicKey, UUID clientId) {
        Approver approver = new Approver();
        approver.id = UUID.randomUUID();
        approver.name = Objects.requireNonNull(name, "name");
        // Parsed and thrown away. The point is to reject a malformed key at registration, where
        // there is somebody to tell, rather than at the first approval, where the only available
        // answer is "that signature did not verify" and the approver would be blamed for it.
        // Ed25519.publicKeyFrom checks the encoding and not the mathematics — see its javadoc for
        // what that does and does not catch.
        Ed25519.publicKeyFrom(publicKey);
        approver.publicKey = publicKey.clone();
        approver.clientId = clientId;
        return approver;
    }

    /**
     * The key to verify this approver's signatures with.
     *
     * @return the parsed public key
     */
    public PublicKey verificationKey() {
        return Ed25519.publicKeyFrom(publicKey);
    }

    /**
     * Whether approving this client's withdrawal would be approving their own.
     *
     * @param withdrawalClientId whose withdrawal is being approved
     * @return true if this approver acts for that client
     */
    public boolean actsFor(UUID withdrawalClientId) {
        return clientId != null && clientId.equals(withdrawalClientId);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * @return a copy of the raw key bytes — the field is an array, and handing it out would let a
     *     caller rewrite this approver's identity through the reference
     */
    public byte[] getPublicKey() {
        return publicKey.clone();
    }

    public UUID getClientId() {
        return clientId;
    }
}
