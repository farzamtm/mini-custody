package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.support.AbstractChainTest;
import com.farzam.custody.support.WithoutKafka;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * The hot wallet's balance, when an address is configured for it.
 *
 * <p>A context of its own, because {@code chain.hot-wallet-address} is empty everywhere else and the
 * empty case is the one the rest of the suite exercises. Two tests, and between them they pin down
 * the thing this field is and is not: it is reported, and it is never a finding.
 *
 * <p>It cannot be a finding in this system, and the reason is worth a test rather than only a
 * comment. Deposits are fabricated by {@code POST /dev/deposits} rather than observed on chain, so
 * the ledger's view of what the custodian holds has no relationship to what the wallet holds. A
 * balance check would fail on every run, and a report that always fails is a report nobody reads.
 */
@SpringBootTest
@WithoutKafka
@TestPropertySource(properties = "chain.hot-wallet-address=0x70997970c51812dc3a010c7d01b50e0d17dc79c8")
class ReconciliationHotWalletTest extends AbstractChainTest {

    @Autowired
    private Reconciler reconciler;

    @Test
    void theReportCarriesWhatTheWalletHolds() {
        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.hotWalletBalanceWei()).hasValueSatisfying(balance -> assertThat(balance).isNotNegative());
    }

    /**
     * And a balance that does not match the ledger is not a discrepancy.
     *
     * <p>It does not match: this address is one of Anvil's development accounts with its genesis
     * allocation, and the ledger knows nothing about it. That the report says nothing about the
     * mismatch is the assertion.
     */
    @Test
    void aBalanceTheLedgerCannotExplainIsNotAFinding() {
        ReconciliationReport report = reconciler.reconcile();

        assertThat(report.discrepancies())
                .noneSatisfy(discrepancy -> assertThat(discrepancy.detail()).containsIgnoringCase("balance"));
    }
}
