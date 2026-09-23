package com.farzam.custody.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigInteger;
import java.util.UUID;

/**
 * One line of the journal: {@code amount} wei moved into {@code accountId}.
 *
 * <p>Append-only. Nothing in this project updates or deletes a row of this table; a mistake is
 * corrected by posting a reversing transaction. The history is the audit trail, and an audit trail
 * you can edit is not one.
 *
 * <p>The account and transaction are plain {@code UUID} columns rather than {@code @ManyToOne}
 * associations. Entries are read in bulk and never navigated one at a time, so an association would
 * buy lazy loading nobody wants and an N+1 query everybody eventually hits.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    /** {@code bigserial}: the database allocates it, so entries are numbered in insertion order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Signed wei: negative debits the account, positive credits it. */
    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger amount;

    protected JournalEntry() {}

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigInteger getAmount() {
        return amount;
    }
}
