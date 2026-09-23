package com.farzam.custody.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The double-entry rule, tested without a database.
 *
 * <p>{@link Posting} is the one piece of the ledger that needs no Spring context and no Postgres,
 * because the invariant it enforces is arithmetic. That makes these the tests that run in
 * microseconds and catch the mistakes most likely to be made.
 */
class PostingTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");

    private final UUID accountA = UUID.randomUUID();
    private final UUID accountB = UUID.randomUUID();

    @Test
    void entriesThatDoNotSumToZeroAreRejected() {
        List<Entry> lopsided = List
                .of(new Entry(accountA, ONE_ETH.negate()), new Entry(accountB, ONE_ETH.add(BigInteger.ONE)));

        assertThatExceptionOfType(UnbalancedEntriesException.class)
                .isThrownBy(() -> new Posting(JournalKind.DEPOSIT, UUID.randomUUID(), lopsided))
                .withMessageContaining("sum to zero");
    }

    @Test
    void aSingleEntryIsNotDoubleEntry() {
        List<Entry> lonely = List.of(new Entry(accountA, ONE_ETH));

        assertThatExceptionOfType(UnbalancedEntriesException.class)
                .isThrownBy(() -> new Posting(JournalKind.DEPOSIT, UUID.randomUUID(), lonely))
                .withMessageContaining("at least two entries");
    }

    @Test
    void anEntryOfZeroMovesNoMoneyAndIsRejected() {
        assertThatExceptionOfType(UnbalancedEntriesException.class)
                .isThrownBy(() -> new Entry(accountA, BigInteger.ZERO));
    }

    @Test
    void aPostingWithoutAKindIsRejected() {
        List<Entry> balanced = List.of(new Entry(accountA, ONE_ETH.negate()), new Entry(accountB, ONE_ETH));

        assertThatNullPointerException().isThrownBy(() -> new Posting(null, UUID.randomUUID(), balanced));
    }

    @Test
    void deltasNetRepeatedAccountsAndSortByAccountId() {
        // Three entries, two accounts: the journal keeps all three, the balance column moves twice.
        Posting posting = new Posting(
                JournalKind.WITHDRAWAL_HOLD,
                UUID.randomUUID(),
                List.of(
                        new Entry(accountA, ONE_ETH.negate()),
                        new Entry(accountB, ONE_ETH),
                        new Entry(accountA, BigInteger.valueOf(-5)),
                        new Entry(accountB, BigInteger.valueOf(5))));

        assertThat(posting.entries()).hasSize(4);
        assertThat(posting.deltas()).hasSize(2)
                .isSortedAccordingTo(Comparator.comparing(Entry::accountId))
                .extracting(Entry::amount)
                .containsExactlyInAnyOrder(
                        ONE_ETH.negate().subtract(BigInteger.valueOf(5)),
                        ONE_ETH.add(BigInteger.valueOf(5)));
    }

    @Test
    void anAccountThatNetsToZeroNeedsNoBalanceUpdate() {
        UUID passthrough = UUID.randomUUID();

        // A moves ONE_ETH to B by way of `passthrough`, which ends where it started.
        Posting posting = new Posting(
                JournalKind.WITHDRAWAL_SETTLE,
                UUID.randomUUID(),
                List.of(
                        new Entry(accountA, ONE_ETH.negate()),
                        new Entry(passthrough, ONE_ETH),
                        new Entry(passthrough, ONE_ETH.negate()),
                        new Entry(accountB, ONE_ETH)));

        assertThat(posting.deltas()).extracting(Entry::accountId).doesNotContain(passthrough).hasSize(2);
    }

    @Test
    void theEntryListIsCopiedSoCallersCannotChangeItLater() {
        List<Entry> mutable = new ArrayList<>(
                List.of(new Entry(accountA, ONE_ETH.negate()), new Entry(accountB, ONE_ETH)));
        Posting posting = new Posting(JournalKind.DEPOSIT, UUID.randomUUID(), mutable);

        mutable.clear();

        assertThat(posting.entries()).hasSize(2);
    }
}
