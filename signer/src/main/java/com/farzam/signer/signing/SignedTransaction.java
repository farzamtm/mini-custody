package com.farzam.signer.signing;

import java.util.UUID;

/**
 * A withdrawal that has been signed, as {@code signing_log} records it.
 *
 * <p><b>The raw bytes are kept, and that is the point of the table.</b> Recovering from a failed
 * broadcast means sending the identical signed transaction again, which the network accepts exactly
 * once because its hash is a hash of its bytes. The alternative — signing again — allocates a new
 * nonce and produces a second valid transaction paying the same person, and if the first one was
 * merely slow rather than lost, both get mined. Storing the bytes is what makes "retry" mean resend
 * rather than re-sign.
 *
 * <p>The hash is known before anything is sent, because it is derived from the signed bytes rather
 * than assigned by the network. That is what lets the {@code WithdrawalBroadcast} event be written
 * in the same transaction as the signature, before the node has been contacted at all.
 *
 * @param withdrawalId which withdrawal; the primary key, so a withdrawal can be signed at most once
 * @param txHash the transaction hash, {@code 0x} and 64 hex characters
 * @param rawTransaction the signed transaction, {@code 0x}-prefixed hex, ready to resend verbatim
 * @param nonce the nonce spent on it
 */
public record SignedTransaction(UUID withdrawalId, String txHash, String rawTransaction, long nonce) {}
