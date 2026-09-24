package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.TestApprover;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalRepository;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.custody.withdrawal.WithdrawalStatus;
import com.farzam.events.ApprovalStatement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The last approval of a quorum, arriving twice at once.
 *
 * <p>This is the race the {@code SELECT … FOR UPDATE} in {@link ApprovalService#submit} exists for,
 * and it has two failure modes that pull in opposite directions. Without any serialisation, both
 * transactions read one existing approval, both conclude the quorum is short, and a withdrawal with
 * every signature it needs sits waiting for one more that nobody will send — quiet, and only
 * noticed by whoever is wondering where their money went. With optimistic locking alone, both
 * conclude the quorum is met, both publish, and the {@code @Version} check rolls one of them back —
 * correct, but it discards a signature somebody meant to give, so an approver is told to sign again
 * for reasons that are not their problem.
 *
 * <p>What is asserted is therefore both halves: two approvals recorded, and exactly one event.
 */
@SpringBootTest
@WithoutKafka
class ApprovalConcurrencyTest extends AbstractPostgresTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger TWO_ETH = new BigInteger("2000000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApproverService registry;

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalRepository withdrawalRepository;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Autowired
    private JdbcClient jdbc;

    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    @Test
    void twoApprovalsArrivingAtOnceAreBothKeptAndPublishOneEvent() throws Exception {
        Withdrawal withdrawal = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, TWO_ETH, UUID.randomUUID().toString()));
        var statement = new ApprovalStatement(withdrawal.getId(), DESTINATION, TWO_ETH);

        Signatory alice = staff();
        Signatory bob = staff();
        var startTogether = new CountDownLatch(1);
        List<Future<?>> submitted = new ArrayList<>();

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (Signatory signatory : List.of(alice, bob)) {
                submitted.add(pool.submit(() -> {
                    startTogether.await(); // pile up here, so the race is a race
                    return approvals.submit(withdrawal.getId(), signatory.id(), signatory.keys().sign(statement));
                }));
            }
            startTogether.countDown();
        } // close() blocks until both tasks have finished

        // Surfaces a failure inside either task rather than letting it hide in a Future. Neither is
        // allowed to fail: a rolled-back approval is a lost signature, which is the outcome the
        // row lock exists to avoid.
        for (Future<?> task : submitted) {
            task.get();
        }

        assertThat(approvalRepository.findByWithdrawalIdOrderByCreatedAtAsc(withdrawal.getId()))
                .as("both signatures were kept")
                .hasSize(2);
        assertThat(withdrawalRepository.findById(withdrawal.getId()).orElseThrow().getStatus())
                .isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(outboxRowsFor(withdrawal.getId())).as("the quorum completed once, so the signer is told once")
                .isEqualTo(1);
    }

    private int outboxRowsFor(UUID withdrawalId) {
        return jdbc.sql("select count(*) from outbox where aggregate_id = :id")
                .param("id", withdrawalId)
                .query(Integer.class)
                .single();
    }

    private record Signatory(UUID id, TestApprover keys) {}

    private Signatory staff() {
        TestApprover keys = TestApprover.generate();
        return new Signatory(registry.register("staff", keys.publicKeyBase64(), null).getId(), keys);
    }
}
