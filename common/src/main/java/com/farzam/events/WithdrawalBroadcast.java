package com.farzam.events;

import java.util.Objects;
import java.util.UUID;

/**
 * "Signed and sent. Here is the transaction hash."
 *
 * <p>Published by the signer on {@code signer.results.v1}, consumed by custody-api, which records
 * the hash and moves the withdrawal to {@code BROADCAST}.
 *
 * <p>Broadcast is not settled. The transaction is in the mempool and may still be dropped, replaced
 * or reverted, so nothing is posted to the ledger here — the hold stays a hold. M6's confirmation
 * watcher is what decides, after three confirmations, that the money really has left.
 *
 * <p>The nonce and the raw transaction are deliberately absent. They are in the signer's
 * {@code signing_log}, which is where a resend has to read them from anyway, and a raw signed
 * transaction on a topic that custody-api reads would hand the funds-moving artefact to the service
 * the architecture is trying to keep away from it.
 *
 * @param withdrawalId which withdrawal
 * @param txHash the transaction hash, {@code 0x} and 64 hex characters
 */
public record WithdrawalBroadcast(UUID withdrawalId, String txHash) {

    /**
     * @throws NullPointerException if any field is missing
     */
    public WithdrawalBroadcast {
        Objects.requireNonNull(withdrawalId, "withdrawalId");
        Objects.requireNonNull(txHash, "txHash");
    }
}
