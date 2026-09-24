package com.farzam.custody.confirmation;

import com.farzam.custody.withdrawal.ConfirmationCount;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers {@link ConfirmationCount} from what the watcher last wrote down.
 *
 * <p>From the stored receipt, not from the chain. The alternative is a JSON-RPC call behind every
 * {@code GET /v1/withdrawals/{id}}, which turns a client polling for a status change into load on
 * the node — and buys a number that is fresher by at most one poll interval, on a quantity that
 * increments every twelve seconds. Nobody can act on that difference.
 */
@Component
class ObservedConfirmations implements ConfirmationCount {

    private final ReceiptStore receipts;

    ObservedConfirmations(ReceiptStore receipts) {
        this.receipts = receipts;
    }

    @Override
    public OptionalInt forWithdrawal(UUID withdrawalId) {
        return receipts.find(withdrawalId)
                .map(receipt -> OptionalInt.of(receipt.confirmations()))
                .orElseGet(OptionalInt::empty);
    }
}
