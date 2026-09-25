package com.farzam.custody.confirmation;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractChainTest;
import com.farzam.custody.support.TestChain;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What M6 adds to the API: a confirmation count on a withdrawal, and the reconciliation report.
 *
 * <p>{@link ConfirmationWatcherIntegrationTest} covers what the watcher decides. This covers what a
 * client can see of it, which is a separate question — the count is denormalised into
 * {@code transaction_receipts} rather than fetched from the chain per request, and a test that
 * asserted on the chain would not notice if that wiring were wrong.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithoutKafka
class ConfirmationApiIntegrationTest extends AbstractChainTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final int REQUIRED = 3;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ConfirmationWatcher watcher;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalApprovalService approvals;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Autowired
    private TransactionTemplate transactions;

    private TestChain chain;
    private String funder;
    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        chain = chain();
        funder = chain.accounts().getFirst();
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    // ---- The confirmation count --------------------------------------------

    /**
     * Null, not zero, while the chain has no receipt.
     *
     * <p>Zero would say "mined, and nothing on top of it", which is a different and briefly-true
     * state. Absent says the chain has not been heard from about this transaction at all — which
     * covers the mempool, a dropped transaction and a reorg alike, none of which a client can act on
     * differently.
     */
    @Test
    void aWithdrawalWithNoReceiptReportsNoConfirmations() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));

        MvcTestResult result = get(withdrawal);

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.confirmations").isNull();
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("BROADCAST");
    }

    @Test
    void aWithdrawalBeingConfirmedReportsHowFarItHasGot() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED - 1);
        watcher.checkBatch();

        MvcTestResult result = get(withdrawal);

        assertThat(result).bodyJson().extractingPath("$.confirmations").isEqualTo(REQUIRED - 1);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("BROADCAST");
    }

    @Test
    void aConfirmedWithdrawalReportsItsHashAndItsDepth() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        watcher.checkBatch();

        MvcTestResult result = get(withdrawal);

        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(result).bodyJson().extractingPath("$.confirmations").isEqualTo(REQUIRED);
        assertThat(result).bodyJson().extractingPath("$.txHash").isEqualTo(withdrawal.getTxHash());
    }

    // ---- Reconciliation ------------------------------------------------------

    @Test
    void theReconciliationReportSaysWhatItCheckedAndWhetherItAgreed() {
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        watcher.checkBatch();

        MvcTestResult result = mvc.get().uri("/v1/reconciliation").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.checkedAt").isNotNull();
        // A count rather than just `agrees`, because a run that checked nothing also agrees, and the
        // two mean entirely different things.
        assertThat(result).bodyJson().extractingPath("$.confirmedChecked").asNumber().isNotNull();
        assertThat(result).bodyJson().extractingPath("$.inFlightChecked").asNumber().isNotNull();
        // All three buckets cross the wire. The contract marks approvedChecked required, so a client
        // that reads it can tell "no withdrawal is stranded" from "nothing was looked at".
        assertThat(result).bodyJson().extractingPath("$.approvedChecked").asNumber().isNotNull();
        assertThat(result).bodyJson().extractingPath("$.discrepancies").isNotNull();
        assertThat(withdrawal.getId()).isNotNull();
    }

    /**
     * A finding crosses the wire with its kind and its sentence, and no client data.
     *
     * <p>Arranged by rolling the chain back past a settled transaction.
     */
    @Test
    void aDiscrepancyIsReportedWithItsKind() {
        String snapshot = chain.snapshot();
        Withdrawal withdrawal = broadcast(chain.send(funder, DESTINATION, POINT_FOUR_ETH));
        chain.mine(REQUIRED);
        watcher.checkBatch();
        chain.revertTo(snapshot);

        MvcTestResult result = mvc.get().uri("/v1/reconciliation").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.agrees").isEqualTo(false);
        assertThat(result).bodyJson()
                .extractingPath("$.discrepancies[?(@.withdrawalId=='" + withdrawal.getId() + "')].kind")
                .asArray()
                .contains("SETTLED_WITHOUT_A_RECEIPT");
    }

    // ---- Fixtures ------------------------------------------------------------

    private MvcTestResult get(Withdrawal withdrawal) {
        return mvc.get().uri("/v1/withdrawals/{id}", withdrawal.getId()).exchange();
    }

    private Withdrawal broadcast(String txHash) {
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        // No approvals: nothing in this file is about who signed off. What an approved withdrawal
        // with genuine signatures on it looks like is ApprovalEventFlowIntegrationTest.
        approvals.approve(requested.getId(), List.of());
        return transactions.execute(status -> {
            Withdrawal withdrawal = withdrawalRepository.findById(requested.getId()).orElseThrow();
            withdrawal.broadcastAs(txHash);
            return withdrawal;
        });
    }
}
