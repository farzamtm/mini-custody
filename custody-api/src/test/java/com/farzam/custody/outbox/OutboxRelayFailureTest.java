package com.farzam.custody.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalApprovalService;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalService;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * What the relay does when the broker is not there.
 *
 * <p>The half of the outbox that justifies the pattern, and the half that is easy to leave untested
 * because it needs something to be broken. The claim is that an unreachable broker is a delay and
 * not a loss: the rows keep their null {@code published_at}, the transaction commits anyway, and the
 * next tick tries again. If instead the rows were marked, or the failure escaped and rolled back
 * work that had succeeded, nobody would find out until a withdrawal sat in {@code APPROVED} forever.
 *
 * <p>The broken broker is real rather than mocked: a second {@link KafkaTemplate} pointed at a port
 * with nothing behind it, with {@code max.block.ms} turned down so it gives up in a quarter of a
 * second. A mock would have to encode an assumption about <em>how</em> the producer fails, and that
 * assumption is exactly the thing worth checking — this producer throws from {@code send} itself
 * rather than returning a future that fails, because it cannot fetch metadata to choose a partition.
 *
 * <p>{@link WithoutKafka}, so the application's own relay timer is off and its listener is not
 * running. The only thing publishing here is the relay this test builds.
 */
@WithoutKafka
@SpringBootTest
class OutboxRelayFailureTest extends AbstractPostgresTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WithdrawalApprovalService approvals;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    @Test
    void anUnreachableBrokerLeavesTheRowForTheNextTick() {
        Withdrawal withdrawal = approvedWithdrawal();

        int published = relayPointedAtNothing().publishBatch();

        assertThat(published).as("nothing went out").isZero();
        assertThat(publishedAtOf(withdrawal.getId())).as("and nothing was marked as though it had").isNull();
    }

    @Test
    void theRowSurvivesRepeatedFailures() {
        Withdrawal withdrawal = approvedWithdrawal();
        OutboxRelay relay = relayPointedAtNothing();

        relay.publishBatch();
        relay.publishBatch();
        relay.publishBatch();

        // The event is not consumed by having been attempted. This is the property that makes the
        // outbox survive an outage of any length rather than only a short one.
        assertThat(publishedAtOf(withdrawal.getId())).isNull();
    }

    /**
     * A relay wired to a producer that cannot reach anything.
     *
     * <p>Built directly rather than taken from the context, so {@code @Transactional} is not applied:
     * without a proxy this runs in autocommit. That is fine for what is being asserted — the failure
     * path never reaches the {@code UPDATE} — and it keeps the test from needing a second Spring
     * context just to swap one bean.
     */
    private OutboxRelay relayPointedAtNothing() {
        var producers = new DefaultKafkaProducerFactory<String, String>(
                Map.of(
                        // Port 1 is reserved and nothing listens on it.
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        "localhost:1",
                        // How long `send` waits for topic metadata before giving up. The default is a
                        // minute, which is the right answer in production and far too long for a test.
                        ProducerConfig.MAX_BLOCK_MS_CONFIG,
                        250,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                        1000,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        500,
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        StringSerializer.class));
        return new OutboxRelay(jdbc, new KafkaTemplate<>(producers), 10, Duration.ofSeconds(1));
    }

    private Withdrawal approvedWithdrawal() {
        UUID clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        UUID accountId = deposits.deposit(clientId, ONE_ETH).getId();
        Withdrawal requested = withdrawals
                .request(new WithdrawalCommand(accountId, DESTINATION, POINT_FOUR_ETH, UUID.randomUUID().toString()));
        return approvals.approve(requested.getId()).orElseThrow();
    }

    private Object publishedAtOf(UUID withdrawalId) {
        return jdbc.sql("select * from outbox where aggregate_id = :aggregateId")
                .param("aggregateId", withdrawalId)
                .query()
                .singleRow()
                .get("published_at");
    }
}
