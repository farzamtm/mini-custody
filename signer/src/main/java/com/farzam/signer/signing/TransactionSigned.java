package com.farzam.signer.signing;

/**
 * An in-process signal that a transaction exists and should now be put on the wire.
 *
 * <p>A Spring application event rather than a direct call, for one reason: it is delivered by
 * {@link org.springframework.transaction.event.TransactionalEventListener} after the database
 * transaction commits. Broadcasting from inside the transaction would be the dual-write problem in
 * its most expensive form — the payment goes out, the commit fails, and there is no record anywhere
 * in this service that a transaction was ever signed, let alone sent. Money leaves and nothing knows.
 *
 * <p>The reverse order is merely inconvenient: commit, then fail to send, and the row sits in
 * {@code signing_log} with a null {@code broadcast_at} until the retry job picks it up and sends the
 * identical bytes.
 *
 * <p>This never crosses a process boundary. The events that do are in {@code common}.
 *
 * @param signed what to broadcast
 */
record TransactionSigned(SignedTransaction signed) {}
