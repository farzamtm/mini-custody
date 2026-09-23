package com.farzam.custody.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.AccountRepository;
import com.farzam.custody.ledger.AccountType;
import com.farzam.custody.ledger.JournalKind;
import com.farzam.custody.ledger.JournalTransactionRepository;
import com.farzam.custody.ledger.LedgerService;
import com.farzam.custody.ledger.SystemAccounts;
import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.whitelist.WhitelistService;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * M2 acceptance test: the withdrawal endpoint, through the real HTTP stack.
 *
 * <p>{@code @SpringBootTest} with MockMvc rather than a {@code @WebMvcTest} slice. A slice would
 * mock the service away, and every criterion below is about what happens when the controller, the
 * service, the ledger and Postgres's unique index are all present at once — half of each assertion
 * is the balance afterwards, not the status code.
 *
 * <p>{@code @ActiveProfiles("dev")} because {@code POST /dev/deposits} is how a balance comes into
 * existence, and it is only mapped under that profile.
 *
 * <p>Every test opens a client with a fresh random id, so client balances are private to a test. The
 * system accounts are not: {@code PENDING_OUT} and {@code EXTERNAL} are singletons shared by the
 * whole class and by whatever ran before it, so assertions about them are written as deltas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WithdrawalApiIntegrationTest extends AbstractPostgresTest {

    private static final BigInteger ONE_ETH = new BigInteger("1000000000000000000");
    private static final BigInteger POINT_FOUR_ETH = new BigInteger("400000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    private static final String CHECKSUMMED = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
    private static final String ELSEWHERE = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JournalTransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private UUID clientId;
    private UUID accountId;
    private BigInteger pendingOutBefore;

    @BeforeEach
    void openAnAccountWithOneEth() {
        clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposit(clientId, ONE_ETH);
        pendingOutBefore = ledger.balanceOf(SystemAccounts.PENDING_OUT);
    }

    // ---- The M2 criteria ---------------------------------------------------

    @Test
    void theSameKeyAndTheSameBodyReturnsTheOriginalAndHoldsOnlyOnce() {
        String key = UUID.randomUUID().toString();

        MvcTestResult first = request(key, accountId, DESTINATION, POINT_FOUR_ETH);
        MvcTestResult second = request(key, accountId, DESTINATION, POINT_FOUR_ETH);

        assertThat(first).hasStatus(202);
        assertThat(second).hasStatus(202);
        assertThat(idOf(second)).as("a retry is the same withdrawal, not a second one").isEqualTo(idOf(first));

        // The assertion that actually matters. Two identical requests move the money once: the
        // client is down 0.4 ETH, not 0.8.
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
        assertThat(heldSinceStart()).isEqualTo(POINT_FOUR_ETH);
        assertThat(transactions.countByKindAndReferenceId(JournalKind.WITHDRAWAL_HOLD, UUID.fromString(idOf(first))))
                .isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();
        request(key, accountId, DESTINATION, POINT_FOUR_ETH);

        MvcTestResult reused = request(key, accountId, DESTINATION, POINT_FOUR_ETH.add(BigInteger.ONE));

        assertThat(reused).hasStatus(409);
        assertThat(reused).bodyJson().extractingPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // Neither held again nor released.
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH.subtract(POINT_FOUR_ETH));
        assertThat(heldSinceStart()).isEqualTo(POINT_FOUR_ETH);
    }

    @Test
    void aDestinationThatIsNotWhitelistedIsRefusedWithoutTouchingTheBalance() {
        MvcTestResult refused = request(UUID.randomUUID().toString(), accountId, ELSEWHERE, POINT_FOUR_ETH);

        assertThat(refused).hasStatus(422);
        assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("ADDRESS_NOT_WHITELISTED");
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH);
        assertThat(heldSinceStart()).isEqualTo(BigInteger.ZERO);
    }

    // The remaining criterion — an illegal transition throws — is unit-tested in
    // WithdrawalStatusTest and WithdrawalTest. It is pure logic and needs no database.

    // ---- The rest of the contract -----------------------------------------

    @Test
    void anAcceptedWithdrawalIsPendingApprovalAndSaysWhereToWatchIt() {
        MvcTestResult accepted = request(UUID.randomUUID().toString(), accountId, DESTINATION, POINT_FOUR_ETH);

        assertThat(accepted).hasStatus(202);
        assertThat(accepted.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/v1/withdrawals/" + idOf(accepted));
        assertThat(accepted).bodyJson().extractingPath("$.status").isEqualTo("PENDING_APPROVAL");
        assertThat(accepted).bodyJson().extractingPath("$.amountWei").isEqualTo(POINT_FOUR_ETH.toString());
        assertThat(accepted).bodyJson().extractingPath("$.destination").isEqualTo(DESTINATION);
        assertThat(accepted).bodyJson().extractingPath("$.clientId").isEqualTo(clientId.toString());

        // Server-side bookkeeping that no client has any business reading back: the key controls the
        // withdrawal, and the hash is how the server decides what a retry means.
        assertThat(bodyOf(accepted)).doesNotContain("idempotencyKey").doesNotContain("requestHash");

        assertThat(mvc.get().uri("/v1/withdrawals/{id}", idOf(accepted))).hasStatus(200)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void theChecksummedSpellingOfAWhitelistedAddressIsWhitelisted() {
        MvcTestResult accepted = request(UUID.randomUUID().toString(), accountId, CHECKSUMMED, POINT_FOUR_ETH);

        assertThat(accepted).hasStatus(202);
        assertThat(accepted).bodyJson().extractingPath("$.destination").isEqualTo(DESTINATION);
    }

    @Test
    void withdrawingMoreThanIsThereIsRefusedAndLeavesTheBalanceAlone() {
        MvcTestResult refused = request(
                UUID.randomUUID().toString(),
                accountId,
                DESTINATION,
                ONE_ETH.add(BigInteger.ONE));

        assertThat(refused).hasStatus(422);
        assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        // The response says what happened without saying how much is in the account.
        assertThat(bodyOf(refused)).doesNotContain(ONE_ETH.toString());
        // The withdrawal row is inserted before the hold is posted, so a balance still at 1 ETH is
        // what proves the transaction rolled back rather than partly succeeded.
        assertThat(ledger.balanceOf(accountId)).isEqualTo(ONE_ETH);
        assertThat(heldSinceStart()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void aSecondWithdrawalCannotSpendTheHeldFunds() {
        request(UUID.randomUUID().toString(), accountId, DESTINATION, POINT_FOUR_ETH);
        request(UUID.randomUUID().toString(), accountId, DESTINATION, POINT_FOUR_ETH);

        // 0.8 of 1 ETH is held. A third 0.4 does not fit — and the hold is what makes that true now,
        // rather than ten minutes later when the signer reaches for money already spoken for.
        MvcTestResult third = request(UUID.randomUUID().toString(), accountId, DESTINATION, POINT_FOUR_ETH);

        assertThat(third).hasStatus(422);
        assertThat(third).bodyJson().extractingPath("$.code").isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(heldSinceStart()).isEqualTo(POINT_FOUR_ETH.add(POINT_FOUR_ETH));
    }

    @Test
    void aWithdrawalFromASystemAccountIsRefused() {
        MvcTestResult refused = request(
                UUID.randomUUID().toString(),
                SystemAccounts.BANK_OPERATING,
                DESTINATION,
                BigInteger.ONE);

        assertThat(refused).hasStatus(422);
        assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("NOT_A_CLIENT_ACCOUNT");
    }

    @Test
    void aWithdrawalFromAnAccountThatDoesNotExistIsRefused() {
        MvcTestResult refused = request(UUID.randomUUID().toString(), UUID.randomUUID(), DESTINATION, BigInteger.ONE);

        assertThat(refused).hasStatus(422);
        assertThat(refused).bodyJson().extractingPath("$.code").isEqualTo("UNKNOWN_ACCOUNT");
    }

    @Test
    void anUnknownWithdrawalIsAFourOhFour() {
        assertThat(mvc.get().uri("/v1/withdrawals/{id}", UUID.randomUUID())).hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("WITHDRAWAL_NOT_FOUND");
    }

    // ---- Validation at the edge -------------------------------------------

    @Test
    void anAmountOfZeroIsRejectedByTheContractBeforeAnyCodeRuns() {
        MvcTestResult rejected = request(UUID.randomUUID().toString(), accountId, DESTINATION, BigInteger.ZERO);

        assertThat(rejected).hasStatus(400);
        assertThat(rejected).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(rejected).bodyJson().extractingPath("$.errors[0].field").isEqualTo("amountWei");
    }

    @Test
    void aDestinationThatIsNotAnAddressIsRejectedByTheContract() {
        MvcTestResult rejected = post(UUID.randomUUID().toString(), """
                {"accountId": "%s", "destination": "not-an-address", "amountWei": "1"}
                """.formatted(accountId));

        assertThat(rejected).hasStatus(400);
        assertThat(rejected).bodyJson().extractingPath("$.errors[0].field").isEqualTo("destination");
    }

    @Test
    void aMissingIdempotencyKeyIsRejected() {
        // Without the key there is no retry safety at all, so the contract marks it required.
        MvcTestResult rejected = mvc.post()
                .uri("/v1/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(accountId, DESTINATION, POINT_FOUR_ETH))
                .exchange();

        assertThat(rejected).hasStatus(400);
    }

    @Test
    void anIdempotencyKeyTooShortToBeUniqueIsRejected() {
        MvcTestResult rejected = request("short", accountId, DESTINATION, POINT_FOUR_ETH);

        assertThat(rejected).hasStatus(400);
        assertThat(rejected).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void aBodyThatIsNotJsonIsRejected() {
        assertThat(post(UUID.randomUUID().toString(), "{not json")).hasStatus(400);
    }

    // ---- Accounts and the whitelist ---------------------------------------

    @Test
    void anAccountReportsItsBalance() {
        MvcTestResult account = mvc.get().uri("/v1/accounts/{id}", accountId).exchange();

        assertThat(account).hasStatus(200);
        assertThat(account).bodyJson().extractingPath("$.balanceWei").isEqualTo(ONE_ETH.toString());
        assertThat(account).bodyJson().extractingPath("$.type").isEqualTo(AccountType.CLIENT.name());
        assertThat(account).bodyJson().extractingPath("$.clientId").isEqualTo(clientId.toString());
        assertThat(account).bodyJson().extractingPath("$.asset").isEqualTo("ETH");
    }

    @Test
    void theExternalAccountIsNegativeAndBelongsToNobody() {
        // The reconciliation invariant M6 will check: −EXTERNAL is what the hot wallet should hold.
        assertThat(ledger.balanceOf(SystemAccounts.EXTERNAL)).isNegative();

        MvcTestResult external = mvc.get().uri("/v1/accounts/{id}", SystemAccounts.EXTERNAL).exchange();

        assertThat(external).hasStatus(200);
        assertThat(external).bodyJson().extractingPath("$.type").isEqualTo(AccountType.EXTERNAL.name());
        assertThat(external).bodyJson().extractingPath("$.clientId").isNull();
    }

    @Test
    void anAccountThatDoesNotExistIsAFourOhFour() {
        assertThat(mvc.get().uri("/v1/accounts/{id}", UUID.randomUUID())).hasStatus(404)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void whitelistingIsIdempotentAndStoresTheAddressInLowerCase() {
        UUID freshClient = UUID.randomUUID();

        MvcTestResult first = whitelistRequest(freshClient, CHECKSUMMED);
        MvcTestResult again = whitelistRequest(freshClient, DESTINATION);

        assertThat(first).hasStatus(201);
        assertThat(first).bodyJson().extractingPath("$.address").isEqualTo(DESTINATION);
        assertThat(again).as("adding the same address twice is not an error").hasStatus(201);
    }

    @Test
    void whitelistingSomethingThatIsNotAnAddressIsRejected() {
        assertThat(whitelistRequest(UUID.randomUUID(), "0xnope")).hasStatus(400);
    }

    // ---- The dev deposit endpoint -----------------------------------------

    @Test
    void aDepositCreatesTheAccountOnFirstUseAndReportsTheBalanceItProduced() {
        UUID freshClient = UUID.randomUUID();

        MvcTestResult created = depositRequest(freshClient, ONE_ETH);

        assertThat(created).hasStatus(201);
        // Not the stale balance the depositing transaction was holding: the ledger writes balances
        // in SQL and not through Hibernate, so this number has to come from a read taken after that
        // transaction committed. See DepositService.
        assertThat(created).bodyJson().extractingPath("$.balanceWei").isEqualTo(ONE_ETH.toString());

        assertThat(depositRequest(freshClient, ONE_ETH)).bodyJson()
                .extractingPath("$.balanceWei")
                .isEqualTo(ONE_ETH.add(ONE_ETH).toString());
    }

    // ---- Helpers -----------------------------------------------------------

    /** How much this test put into the shared PENDING_OUT account, ignoring everyone else's. */
    private BigInteger heldSinceStart() {
        return ledger.balanceOf(SystemAccounts.PENDING_OUT).subtract(pendingOutBefore);
    }

    private UUID deposit(UUID client, BigInteger amount) {
        // Asserted rather than assumed: a broken fixture that silently produced no account would
        // make every test below fail somewhere far away from the cause.
        assertThat(depositRequest(client, amount)).hasStatus(201);
        return accounts.findByClientIdAndType(client, AccountType.CLIENT).orElseThrow().getId();
    }

    private MvcTestResult depositRequest(UUID client, BigInteger amount) {
        return mvc.post()
                .uri("/dev/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\": \"%s\", \"amountWei\": \"%s\"}".formatted(client, amount))
                .exchange();
    }

    private MvcTestResult request(String key, UUID account, String destination, BigInteger amount) {
        return post(key, body(account, destination, amount));
    }

    private MvcTestResult post(String key, String json) {
        return mvc.post()
                .uri("/v1/withdrawals")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private MvcTestResult whitelistRequest(UUID client, String address) {
        return mvc.post()
                .uri("/v1/clients/{id}/whitelist", client)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\": \"%s\"}".formatted(address))
                .exchange();
    }

    private static String body(UUID account, String destination, BigInteger amount) {
        return """
                {"accountId": "%s", "destination": "%s", "amountWei": "%s"}
                """.formatted(account, destination, amount);
    }

    private static String idOf(MvcTestResult result) {
        return JsonPath.read(bodyOf(result), "$.id");
    }

    private static String bodyOf(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("the response is UTF-8 and has been since Servlet 3", impossible);
        }
    }
}
