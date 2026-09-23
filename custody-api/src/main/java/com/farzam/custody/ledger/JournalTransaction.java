package com.farzam.custody.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One business event that moved money: the header row its entries hang off.
 *
 * <p>The {@code unique (kind, reference_id)} constraint behind {@link #kind} and
 * {@link #referenceId} is the ledger's idempotency guarantee. It is a database constraint rather
 * than an application check because the duplicate usually arrives on another thread, in another
 * process, at the same instant — and only the database sees both.
 *
 * <p>Read-only from JPA's side: rows are inserted by {@link JournalWriter} through SQL.
 */
@Entity
@Table(name = "journal_transactions")
public class JournalTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalKind kind;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    /** Filled by the column default. Marked non-insertable so Hibernate never overwrites it. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected JournalTransaction() {}

    public UUID getId() {
        return id;
    }

    public JournalKind getKind() {
        return kind;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
