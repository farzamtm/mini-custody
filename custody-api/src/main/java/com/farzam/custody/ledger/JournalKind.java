package com.farzam.custody.ledger;

/**
 * The business event behind a journal transaction.
 *
 * <p>Together with a reference id (the withdrawal id, the deposit id) this forms the ledger's
 * idempotency key: {@code unique (kind, reference_id)} means "settle withdrawal X" can be booked
 * once and once only, however many times an at-least-once Kafka delivery asks for it.
 *
 * <p>Stored as text, never as the enum's ordinal — reordering this list must not silently rewrite
 * history.
 */
public enum JournalKind {

    /** Money arriving from the chain: EXTERNAL −amount, CLIENT +amount. */
    DEPOSIT,

    /** A withdrawal was requested: CLIENT −amount, PENDING_OUT +amount. */
    WITHDRAWAL_HOLD,

    /** A withdrawal confirmed on chain: PENDING_OUT −amount, EXTERNAL +amount. */
    WITHDRAWAL_SETTLE,

    /** A withdrawal was rejected or failed: PENDING_OUT −amount, CLIENT +amount. */
    WITHDRAWAL_RELEASE,

    /** Gas paid to miners: BANK_OPERATING −fee, EXTERNAL +fee. */
    NETWORK_FEE
}
