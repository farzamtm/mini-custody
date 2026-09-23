package com.farzam.custody.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigInteger;
import java.util.Objects;
import java.util.UUID;

/**
 * An account in the double-entry ledger.
 *
 * <p>{@code balance} is a cache. The authoritative record is the list of journal entries pointing
 * at this account, and {@link LedgerService#recomputedBalanceOf(UUID)} adds them up to prove the
 * two still agree. Keeping the cache is what makes "can this client afford it?" one indexed row
 * read instead of a full scan of their history.
 *
 * <p>Reads go through this entity; writes do not. The balance column is only ever moved by the SQL
 * in {@link PessimisticBalanceUpdater} or {@link OptimisticBalanceUpdater}, where the locking is
 * visible. See ADR 0002.
 *
 * <p>{@code final} because the constructor validates, and a non-final class with a throwing
 * constructor can be subclassed into a half-built instance (SpotBugs' {@code CT_CONSTRUCTOR_THROW}).
 * The cost is that Hibernate cannot make a lazy proxy of it, which costs nothing here: no
 * association points at an account, so it is only ever loaded outright.
 */
@Entity
@Table(name = "accounts")
public final class Account {

    @Id
    private UUID id;

    /** Null for the system accounts: EXTERNAL and BANK_OPERATING belong to nobody. */
    @Column(name = "client_id")
    private UUID clientId;

    /**
     * {@code EnumType.STRING}, never the default {@code ORDINAL}. Ordinals store the enum's
     * position, so inserting a constant in the middle of {@link AccountType} would silently
     * relabel every existing row.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private String asset;

    /** Wei. {@code numeric(78,0)} in Postgres: an exact integer wide enough for 2^256. */
    @Column(nullable = false, precision = 78, scale = 0)
    private BigInteger balance;

    /**
     * The optimistic-locking counter.
     *
     * <p>Declared as a nullable {@code Long} rather than {@code long} on purpose: Spring Data reads
     * "version is null" as "this entity is new", which turns {@code save()} on a fresh account into
     * a plain INSERT instead of a SELECT-then-INSERT merge.
     *
     * <p>Hibernate maintains it for entity writes. The ledger's own SQL bumps it by hand, because
     * the ledger does not write through Hibernate — see {@link OptimisticBalanceUpdater}.
     */
    @Version
    private Long version;

    /** JPA requires a no-arg constructor; it is not part of the API. */
    Account() {}

    public Account(UUID id, UUID clientId, AccountType type) {
        this.id = Objects.requireNonNull(id, "id");
        this.clientId = clientId;
        this.type = Objects.requireNonNull(type, "type");
        this.asset = "ETH";
        this.balance = BigInteger.ZERO;
    }

    public static Account forClient(UUID clientId) {
        return new Account(UUID.randomUUID(), clientId, AccountType.CLIENT);
    }

    public static Account system(AccountType type) {
        return new Account(UUID.randomUUID(), null, type);
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientId() {
        return clientId;
    }

    public AccountType getType() {
        return type;
    }

    public String getAsset() {
        return asset;
    }

    public BigInteger getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }
}
