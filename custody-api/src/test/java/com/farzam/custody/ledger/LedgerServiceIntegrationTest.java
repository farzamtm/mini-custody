package com.farzam.custody.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.WithoutKafka;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M1 acceptance test for the single-threaded behaviour of the ledger.
 *
 * <p>Runs against the default locking strategy; the concurrency behaviour, where the two strategies
 * differ, is in {@link PessimisticLedgerConcurrencyTest} and {@link OptimisticLedgerConcurrencyTest}.
 */
@WithoutKafka
@SpringBootTest
class LedgerServiceIntegrationTest extends AbstractPostgresTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JournalTransactionRepository transactions;

    @Autowired
    private JournalEntryRepository entries;

    private UUID clientId;
    private Account external;
    private Account client;
    private Account pendingOut;

    @BeforeEach
    void openAccounts() {
        clientId = UUID.randomUUID();
        external = accounts.save(Account.system(AccountType.EXTERNAL));
        client = accounts.save(Account.forClient(clientId));
        pendingOut = accounts.save(Account.system(AccountType.PENDING_OUT));
    }

    @Test
    void anAccountStartsEmptyAndFindsItselfByClientAndType() {
        assertThat(client.getBalance()).isEqualTo(BigInteger.ZERO);
        assertThat(client.getAsset()).isEqualTo("ETH");
        assertThat(client.getClientId()).isEqualTo(clientId);
        assertThat(client.getVersion()).isZero();
        assertThat(external.getClientId()).isNull();

        assertThat(accounts.findByClientIdAndType(clientId, AccountType.CLIENT)).map(Account::getId)
                .contains(client.getId());
    }

    @Test
    void aDepositMovesBothSidesAndLetsExternalGoNegative() {
        UUID depositId = UUID.randomUUID();

        UUID transactionId = ledger.post(JournalKind.DEPOSIT, depositId, deposit(ONE_ETH));

        assertThat(ledger.balanceOf(client.getId())).isEqualTo(ONE_ETH);
        // EXTERNAL is the outside world: its negation is what the hot wallet should hold on chain.
        assertThat(ledger.balanceOf(external.getId())).isEqualTo(ONE_ETH.negate());

        JournalTransaction booked = transactions.findById(transactionId).orElseThrow();
        assertThat(booked.getKind()).isEqualTo(JournalKind.DEPOSIT);
        assertThat(booked.getReferenceId()).isEqualTo(depositId);
        assertThat(booked.getCreatedAt()).isNotNull();

        assertThat(entries.findByTransactionId(transactionId)).hasSize(2)
                .allSatisfy(entry -> assertThat(entry.getId()).isNotNull())
                .extracting(JournalEntry::getAmount)
                .containsExactlyInAnyOrder(ONE_ETH, ONE_ETH.negate());
    }

    @Test
    void theCachedBalanceAlwaysEqualsTheSumOfTheJournal() {
        ledger.post(JournalKind.DEPOSIT, UUID.randomUUID(), deposit(ONE_ETH));
        ledger.post(JournalKind.WITHDRAWAL_HOLD, UUID.randomUUID(), hold(POINT_FOUR_ETH));

        for (Account account : List.of(external, client, pendingOut)) {
            assertThat(ledger.recomputedBalanceOf(account.getId())).as("recomputed balance of %s", account.getType())
                    .isEqualTo(ledger.balanceOf(account.getId()));
        }
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
        assertThat(ledger.balanceOf(pendingOut.getId())).isEqualTo(POINT_FOUR_ETH);
    }

    @Test
    void theSameKindAndReferenceIsBookedOnlyOnce() {
        ledger.post(JournalKind.DEPOSIT, UUID.randomUUID(), deposit(ONE_ETH));
        UUID withdrawalId = UUID.randomUUID();

        UUID first = ledger.post(JournalKind.WITHDRAWAL_HOLD, withdrawalId, hold(POINT_FOUR_ETH));
        UUID second = ledger.post(JournalKind.WITHDRAWAL_HOLD, withdrawalId, hold(POINT_FOUR_ETH));

        // The second call is a no-op that reports the original transaction, which is what lets a
        // redelivered Kafka message be handled rather than guarded against.
        assertThat(second).isEqualTo(first);
        assertThat(transactions.countByKindAndReferenceId(JournalKind.WITHDRAWAL_HOLD, withdrawalId)).isEqualTo(1);
        assertThat(entries.findByTransactionId(first)).hasSize(2);
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
    }

    @Test
    void theSameReferenceUnderADifferentKindIsADifferentEvent() {
        ledger.post(JournalKind.DEPOSIT, UUID.randomUUID(), deposit(ONE_ETH));
        UUID withdrawalId = UUID.randomUUID();

        UUID held = ledger.post(JournalKind.WITHDRAWAL_HOLD, withdrawalId, hold(POINT_FOUR_ETH));
        UUID released = ledger.post(
                JournalKind.WITHDRAWAL_RELEASE,
                withdrawalId,
                List.of(
                        new Entry(pendingOut.getId(), POINT_FOUR_ETH.negate()),
                        new Entry(client.getId(), POINT_FOUR_ETH)));

        assertThat(released).isNotEqualTo(held);
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(ONE_ETH);
        assertThat(ledger.balanceOf(pendingOut.getId())).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void spendingMoreThanIsThereFailsAndLeavesNoTraceAtAll() {
        ledger.post(JournalKind.DEPOSIT, UUID.randomUUID(), deposit(POINT_FOUR_ETH));
        UUID withdrawalId = UUID.randomUUID();

        assertThatExceptionOfType(InsufficientFundsException.class)
                .isThrownBy(() -> ledger.post(JournalKind.WITHDRAWAL_HOLD, withdrawalId, hold(ONE_ETH)))
                .satisfies(failure -> assertThat(failure.accountId()).isEqualTo(client.getId()));

        // The header row was inserted before the balance check, so proving it is gone proves the
        // @Transactional rollback actually happened rather than being assumed.
        assertThat(transactions.countByKindAndReferenceId(JournalKind.WITHDRAWAL_HOLD, withdrawalId)).isZero();
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(POINT_FOUR_ETH);
        assertThat(ledger.balanceOf(pendingOut.getId())).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void postingToAnAccountThatDoesNotExistIsRejectedByName() {
        UUID ghost = UUID.randomUUID();
        UUID reference = UUID.randomUUID();

        assertThatExceptionOfType(UnknownAccountException.class)
                .isThrownBy(
                        () -> ledger.post(
                                JournalKind.DEPOSIT,
                                reference,
                                List.of(new Entry(ghost, ONE_ETH.negate()), new Entry(client.getId(), ONE_ETH))))
                .satisfies(failure -> assertThat(failure.accountId()).isEqualTo(ghost));

        assertThat(transactions.countByKindAndReferenceId(JournalKind.DEPOSIT, reference)).isZero();
    }

    @Test
    void readingTheBalanceOfAnUnknownAccountIsAnError() {
        UUID ghost = UUID.randomUUID();

        assertThatExceptionOfType(UnknownAccountException.class).isThrownBy(() -> ledger.balanceOf(ghost));
    }

    @Test
    void anAccountTouchedTwiceInOnePostingMovesOnceButIsJournalledTwice() {
        ledger.post(JournalKind.DEPOSIT, UUID.randomUUID(), deposit(ONE_ETH));
        BigInteger fee = BigInteger.valueOf(21_000L);

        UUID transactionId = ledger.post(
                JournalKind.WITHDRAWAL_HOLD,
                UUID.randomUUID(),
                List.of(
                        new Entry(client.getId(), POINT_FOUR_ETH.negate()),
                        new Entry(pendingOut.getId(), POINT_FOUR_ETH),
                        new Entry(client.getId(), fee.negate()),
                        new Entry(pendingOut.getId(), fee)));

        assertThat(entries.findByTransactionId(transactionId)).hasSize(4);
        assertThat(ledger.balanceOf(client.getId())).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH).subtract(fee));
        assertThat(ledger.recomputedBalanceOf(client.getId())).isEqualTo(ledger.balanceOf(client.getId()));
    }

    private List<Entry> deposit(BigInteger amount) {
        return List.of(new Entry(external.getId(), amount.negate()), new Entry(client.getId(), amount));
    }

    private List<Entry> hold(BigInteger amount) {
        return List.of(new Entry(client.getId(), amount.negate()), new Entry(pendingOut.getId(), amount));
    }
}
