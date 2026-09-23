package com.farzam.custody.ledger;

import java.util.UUID;

/**
 * The ids of the accounts that belong to nobody, seeded by {@code V3__system_accounts.sql}.
 *
 * <p>Constants rather than a lookup. A posting is a list of account ids, and for the system side of
 * it the id is knowable at compile time — querying for "the EXTERNAL account" would be a round trip
 * to learn something the schema already fixed, and it would invite the question of what to do when
 * the query returns two rows.
 *
 * <p>The flip side is that these three values exist in two places, here and in the migration. They
 * are checked against each other by {@code SystemAccountsTest}, which is cheaper than the machinery
 * needed to have only one copy.
 */
public final class SystemAccounts {

    /** The outside world. Runs negative; its negation is what the hot wallet should hold on chain. */
    public static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Where a withdrawal's funds sit between "requested" and "confirmed or released". */
    public static final UUID PENDING_OUT = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** The bank's own money, which is what pays the gas. */
    public static final UUID BANK_OPERATING = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private SystemAccounts() {}
}
