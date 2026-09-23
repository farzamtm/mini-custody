package com.farzam.custody.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

    /** Backed by the {@code unique (kind, reference_id)} index, so it is a single index lookup. */
    Optional<JournalTransaction> findByKindAndReferenceId(JournalKind kind, UUID referenceId);

    long countByKindAndReferenceId(JournalKind kind, UUID referenceId);
}
