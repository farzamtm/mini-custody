package com.farzam.custody.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    /** Uses the {@code journal_entries_account} index from V1. */
    List<JournalEntry> findByAccountId(UUID accountId);

    List<JournalEntry> findByTransactionId(UUID transactionId);
}
