package com.farzam.custody.confirmation;

import com.farzam.custody.api.ReconciliationApi;
import com.farzam.custody.api.model.DiscrepancyDto;
import com.farzam.custody.api.model.ReconciliationDto;
import java.math.BigInteger;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/reconciliation} — ask the chain whether the ledger is telling the truth.
 *
 * <p>Synchronous, and it makes one JSON-RPC call per settled or in-flight withdrawal. At real volume
 * that is a scheduled job writing to a report rather than a request anybody waits on; it is an
 * endpoint here because being able to ask the question by hand is what makes the property
 * demonstrable rather than asserted.
 *
 * <p>Not under the {@code dev} profile, unlike the endpoints that fabricate state. This one only
 * reads, and "is the ledger still consistent with the chain" is a question a production deployment
 * wants answerable more than a laptop does.
 */
@RestController
class ReconciliationController implements ReconciliationApi {

    private final Reconciler reconciler;

    ReconciliationController(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public ResponseEntity<ReconciliationDto> reconcile() {
        ReconciliationReport report = reconciler.reconcile();

        return ResponseEntity.ok(
                new ReconciliationDto(
                        // The report holds an Instant — a moment, with no opinion about where on
                        // earth anyone was. The contract says date-time, so it needs an offset, and
                        // UTC is the only one that is not a guess.
                        report.checkedAt().atOffset(ZoneOffset.UTC),
                        report.confirmedChecked(),
                        report.inFlightChecked(),
                        report.approvedChecked(),
                        report.agrees(),
                        report.discrepancies().stream().map(ReconciliationController::describe).toList())
                        .hotWalletBalanceWei(report.hotWalletBalanceWei().map(BigInteger::toString).orElse(null)));
    }

    private static DiscrepancyDto describe(Discrepancy discrepancy) {
        return new DiscrepancyDto(
                discrepancy.withdrawalId(),
                DiscrepancyDto.KindEnum.fromValue(discrepancy.kind().name()),
                discrepancy.detail());
    }
}
