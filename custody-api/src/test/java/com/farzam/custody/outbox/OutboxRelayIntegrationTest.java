package com.farzam.custody.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractKafkaTest;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.events.EventEnvelope;
import com.farzam.events.EventJson;
import com.farzam.events.EventType;
import com.farzam.events.Topics;
import com.farzam.events.WithdrawalApproved;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The relay's mechanics, against a real broker and a real Postgres.
 *
 * <p>The timer is off. Every test here drives {@link OutboxRelay#publishBatch()} itself, because the
 * questions being asked — how many rows did that batch take, what did two relays do to each other —
 * have no answer if a background thread is also publishing. {@code OutboxScheduling} is a separate
 * bean precisely so it can be switched off here.
 *
 * <p>The batch size is one. It makes the batching visible, and it forces the concurrency test to
 * genuinely interleave: with the default of a hundred, the first thread to arrive would take every
 * row and the second would find an empty table, which proves nothing about {@code SKIP LOCKED}.
 *
 * <p>The listener is off too. This class never publishes to {@code signer.results.v1}, and a second
 * consumer in the {@code custody-api} group while another test class holds a context open is a
 * rebalance nobody needs.
 */
@SpringBootTest(
        properties = {"outbox.relay.scheduled=false", "outbox.relay.batch-size=1",
                "spring.kafka.listener.auto-startup=false"})
class OutboxRelayIntegrationTest extends AbstractKafkaTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    /** Long enough for a local broker to hand back what it was given a moment ago. */
    private static final Duration LISTEN = Duration.ofSeconds(3);

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalApprovalService approvals;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Autowired
    private JdbcClient jdbc;

    // ---- One row, one message ----------------------------------------------

    @Test
    void anApprovedWithdrawalBecomesExactlyOneMessageKeyedByItsId() {
        Withdrawal withdrawal = approvedWithdrawal();

        // The row exists and is unpublished before the relay runs: the approval wrote it, in the
        // same transaction that changed the status, and nothing has been sent yet.
        assertThat(publishedAtOf(withdrawal.getId())).as("written but not yet sent").isNull();

        publishEverything();

        List<ConsumerRecord<String, String>> published = drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN);
        assertThat(published).as("exactly one WithdrawalApproved, not none and not two").hasSize(1);

        ConsumerRecord<String, String> record = published.getFirst();
        assertThat(record.key()).as("keyed by withdrawal id, so one withdrawal's events share a partition and an order")
                .isEqualTo(withdrawal.getId().toString());

        EventEnvelope envelope = EventJson.read(record.value(), EventEnvelope.class);
        assertThat(envelope.eventType()).isEqualTo(EventType.WITHDRAWAL_APPROVED);
        assertThat(envelope.aggregateId()).isEqualTo(withdrawal.getId());
        assertThat(envelope.eventId())
                .as("the event id is the outbox row's id, which is what makes a resend recognisable")
                .isEqualTo(outboxIdOf(withdrawal.getId()));

        WithdrawalApproved payload = envelope.payloadAs(WithdrawalApproved.class);
        assertThat(payload.withdrawalId()).isEqualTo(withdrawal.getId());
        assertThat(payload.destination()).isEqualTo(DESTINATION);
        assertThat(payload.amountWei()).isEqualTo(POINT_FOUR_ETH);
        assertThat(payload.approvals()).as("this fixture approves with nobody approving").isEmpty();
    }

    @Test
    void aPublishedRowIsMarkedAndNeverSentAgain() {
        Withdrawal withdrawal = approvedWithdrawal();
        publishEverything();

        assertThat(publishedAtOf(withdrawal.getId())).as("marked, so the next tick skips it").isNotNull();
        // The partial index the relay reads only contains unpublished rows, so "marked" and "not in
        // the next batch" are the same statement. Asserting the second one directly anyway: this is
        // the property that stops a working relay from republishing the whole table every 500 ms.
        assertThat(relay.publishBatch()).as("nothing left to publish").isZero();
        assertThat(drain(Topics.WITHDRAWALS, withdrawal.getId(), LISTEN)).hasSize(1);
    }

    // ---- Two relays -------------------------------------------------------

    @Test
    void twoRelaysDrainTheSameBacklogWithoutPublishingAnythingTwice() throws Exception {
        // Start from an empty backlog, so the count below is about this test's rows and nothing
        // else. Tests share a database, and OutboxRelayFailureTest leaves rows deliberately
        // unpublished — its whole point is that a failed send does not consume the event.
        clearBacklog();

        // Six rows, two threads, one row per batch. Both threads are live at once and both are
        // claiming from the same table, which is the situation SKIP LOCKED exists for: without it
        // the second thread would block on the first thread's locked row and then publish it again
        // when the lock was released.
        Set<UUID> approved = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            approved.add(approvedWithdrawal().getId());
        }

        var startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(drainer(startTogether), drainer(startTogether)));
            int published = results.getFirst().get() + results.getLast().get();
            assertThat(published).as("every row published, and none of them twice").isEqualTo(approved.size());
        } finally {
            pool.shutdownNow();
        }

        Map<String, Long> perWithdrawal = drain(Topics.WITHDRAWALS, LISTEN).stream()
                .filter(record -> approved.contains(UUID.fromString(record.key())))
                .collect(Collectors.groupingBy(ConsumerRecord::key, Collectors.counting()));

        assertThat(perWithdrawal).hasSize(approved.size());
        assertThat(perWithdrawal.values()).as("one message each, from whichever relay got there first")
                .allSatisfy(count -> assertThat(count).isEqualTo(1L));
    }

    /** Publishes one row at a time until there is nothing left, counting what it sent. */
    private Callable<Integer> drainer(CyclicBarrier startTogether) {
        return () -> {
            startTogether.await();
            int published = 0;
            for (int sent = relay.publishBatch(); sent > 0; sent = relay.publishBatch()) {
                published += sent;
            }
            return published;
        };
    }

    // ---- Fixtures ----------------------------------------------------------

    /**
     * Publishes until the backlog is empty.
     *
     * <p>Not a single {@code publishBatch()}: the batch size is one and the tests share a database,
     * so another test's unpublished row may well be ahead of this test's in the queue.
     */
    private void publishEverything() {
        List<Integer> batches = new ArrayList<>();
        for (int sent = relay.publishBatch(); sent > 0; sent = relay.publishBatch()) {
            batches.add(sent);
        }
        assertThat(batches).as("the relay published something").isNotEmpty();
    }

    /** The same loop, without the assertion, for emptying the table before a test rather than in it. */
    private void clearBacklog() {
        for (int sent = relay.publishBatch(); sent > 0; sent = relay.publishBatch()) {
            // Draining, not asserting.
        }
    }

    private Withdrawal approvedWithdrawal() {
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        UUID accountId = deposits.deposit(clientId, ONE_ETH).getId();
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        // No approvals: these tests are about the relay moving a row to Kafka, and the evidence on
        // the event is not what they are asking about. Collecting real signatures here would make
        // every assertion about the outbox depend on the approval path as well.
        return approvals.approve(requested.getId(), List.of()).orElseThrow();
    }

    private Object publishedAtOf(UUID withdrawalId) {
        return single(withdrawalId, "published_at");
    }

    private UUID outboxIdOf(UUID withdrawalId) {
        return (UUID) single(withdrawalId, "id");
    }

    private Object single(UUID withdrawalId, String column) {
        Map<String, Object> row = jdbc.sql("select * from outbox where aggregate_id = :aggregateId")
                .param("aggregateId", withdrawalId)
                .query()
                .singleRow();
        return row.get(column);
    }
}
