package com.farzam.custody.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.farzam.custody.ledger.DepositService;
import com.farzam.custody.support.AbstractPostgresTest;
import com.farzam.custody.support.TestApprover;
import com.farzam.custody.support.WithoutKafka;
import com.farzam.custody.whitelist.WhitelistService;
import com.farzam.custody.withdrawal.Withdrawal;
import com.farzam.custody.withdrawal.WithdrawalCommand;
import com.farzam.custody.withdrawal.WithdrawalService;
import com.farzam.events.ApprovalStatement;
import com.jayway.jsonpath.JsonPath;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The approval endpoints through the real HTTP stack: status codes, problem codes, and what a
 * response does and does not say.
 *
 * <p>{@link ApprovalServiceIntegrationTest} covers the decisions. This covers the contract — that
 * each refusal arrives as the documented {@code code}, because a client is supposed to branch on
 * that rather than on the status, and three different things here return {@code 422}.
 *
 * <p>{@code @ActiveProfiles("dev")} for the two endpoints that only exist under it: deposits, which
 * is how a balance comes into being, and approver registration.
 */
@WithoutKafka
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ApprovalApiIntegrationTest extends AbstractPostgresTest {

    private static final BigInteger TEN_ETH = new BigInteger("10000000000000000000");
    private static final BigInteger TWO_ETH = new BigInteger("2000000000000000000");
    private static final BigInteger HALF_ETH = new BigInteger("500000000000000000");
    private static final String DESTINATION = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private WithdrawalService withdrawals;

    @Autowired
    private WhitelistService whitelist;

    @Autowired
    private DepositService deposits;

    private UUID clientId;
    private UUID accountId;

    @BeforeEach
    void fundAClient() {
        clientId = UUID.randomUUID();
        whitelist.allow(clientId, DESTINATION);
        accountId = deposits.deposit(clientId, TEN_ETH).getId();
    }

    // ---- Registering an approver -------------------------------------------

    @Test
    void registeringAnApproverReturnsItsIdAndNotItsKey() throws UnsupportedEncodingException {
        TestApprover keys = TestApprover.generate();

        MvcTestResult result = mvc.post().uri("/dev/approvers").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"alice","publicKey":"%s"}
                """.formatted(keys.publicKeyBase64())).exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("alice");
        assertThat(result).bodyJson().extractingPath("$.id").isNotNull();
        // The key is the caller's own and they already have it. A registry that reads keys back out
        // is one more way to enumerate who can authorise a payment.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("publicKey");
    }

    @Test
    void aPublicKeyOfTheWrongShapeIsRejectedAtTheEdge() {
        MvcTestResult result = mvc.post().uri("/dev/approvers").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"alice","publicKey":"too-short"}
                """).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    // ---- Approving ----------------------------------------------------------

    @Test
    void anApprovalThatCompletesTheQuorumSaysSo() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();

        MvcTestResult result = approve(withdrawal, alice);

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.collected").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.required").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("APPROVED");
        assertThat(result).bodyJson().extractingPath("$.approverId").isEqualTo(alice.id().toString());
    }

    @Test
    void anApprovalThatDoesNotCompleteTheQuorumSaysThatToo() {
        Withdrawal withdrawal = request(TWO_ETH);

        MvcTestResult result = approve(withdrawal, staff());

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.collected").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.required").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void approvingAWithdrawalThatDoesNotExistIsANotFound() {
        Signatory alice = staff();

        MvcTestResult result = mvc.post()
                .uri("/v1/withdrawals/{id}/approvals", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(alice.id(), unsignedBytes()))
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("WITHDRAWAL_NOT_FOUND");
    }

    // ---- The three 422s, which is why clients branch on `code` --------------

    @Test
    void anUnknownApproverIsUnprocessable() {
        Withdrawal withdrawal = request(HALF_ETH);

        MvcTestResult result = mvc.post()
                .uri("/v1/withdrawals/{id}/approvals", withdrawal.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID(), unsignedBytes()))
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNKNOWN_APPROVER");
    }

    @Test
    void aSignatureThatDoesNotVerifyIsUnprocessableAndSaysNothingElse() throws UnsupportedEncodingException {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();
        var elsewhere = new ApprovalStatement(withdrawal.getId(), "0x" + "a".repeat(40), HALF_ETH);

        MvcTestResult result = mvc.post()
                .uri("/v1/withdrawals/{id}/approvals", withdrawal.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(alice.id(), alice.keys().sign(elsewhere)))
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_APPROVAL_SIGNATURE");
        // Which of the several ways it could fail is information about how close a forgery got, and
        // there is no authentication on this API — the body is a reply to a stranger.
        assertThat(result.getResponse().getContentAsString()).doesNotContain(alice.id().toString())
                .doesNotContain("0xaaaa");
    }

    @Test
    void aSelfApprovalIsUnprocessable() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory theirOwn = register("the client's own", clientId);

        MvcTestResult result = approve(withdrawal, theirOwn);

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SELF_APPROVAL");
    }

    // ---- The two 409s -------------------------------------------------------

    @Test
    void theSameApproverTwiceIsAConflict() {
        Withdrawal withdrawal = request(TWO_ETH);
        Signatory alice = staff();
        approve(withdrawal, alice);

        MvcTestResult again = approve(withdrawal, alice);

        assertThat(again).hasStatus(409);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("ALREADY_APPROVED");
    }

    @Test
    void approvingSomethingAlreadyApprovedIsAConflict() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory bob = staff();
        approve(withdrawal, staff());

        MvcTestResult result = approve(withdrawal, bob);

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ILLEGAL_STATE_TRANSITION");
    }

    // ---- The edge -----------------------------------------------------------

    @Test
    void aSignatureOfTheWrongLengthIsRejectedBeforeItReachesTheService() {
        Withdrawal withdrawal = request(HALF_ETH);
        Signatory alice = staff();

        MvcTestResult result = mvc.post()
                .uri("/v1/withdrawals/{id}/approvals", withdrawal.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(alice.id(), Base64.getEncoder().encodeToString(new byte[32])))
                .exchange();

        // 400, not 422. Sixty-four bytes is what an Ed25519 signature is; thirty-two of them is a
        // malformed request rather than a failed verification, and the two deserve different codes.
        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    // ---- Fixtures -----------------------------------------------------------

    private record Signatory(UUID id, TestApprover keys) {}

    private Withdrawal request(BigInteger amount) {
        return withdrawals.request(new WithdrawalCommand(accountId, DESTINATION, amount, UUID.randomUUID().toString()));
    }

    private Signatory staff() {
        return register("staff", null);
    }

    /** Registered over HTTP, so the dev endpoint is on the path of every test here rather than one. */
    private Signatory register(String name, UUID actsFor) {
        TestApprover keys = TestApprover.generate();
        String json = actsFor == null ? """
                {"name":"%s","publicKey":"%s"}
                """.formatted(name, keys.publicKeyBase64()) : """
                {"name":"%s","publicKey":"%s","clientId":"%s"}
                """.formatted(name, keys.publicKeyBase64(), actsFor);

        MvcTestResult result = mvc.post()
                .uri("/dev/approvers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
        assertThat(result).hasStatus(201);
        return new Signatory(UUID.fromString(read(result, "$.id")), keys);
    }

    private MvcTestResult approve(Withdrawal withdrawal, Signatory signatory) {
        String signature = signatory.keys()
                .sign(withdrawal.getId(), withdrawal.getDestination(), withdrawal.getAmount());
        return mvc.post()
                .uri("/v1/withdrawals/{id}/approvals", withdrawal.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(signatory.id(), signature))
                .exchange();
    }

    private static String body(UUID approverId, String signature) {
        return """
                {"approverId":"%s","signature":"%s"}
                """.formatted(approverId, signature);
    }

    /** 64 zero bytes: the right shape, and a signature over nothing. */
    private static String unsignedBytes() {
        return Base64.getEncoder().encodeToString(new byte[64]);
    }

    private static String read(MvcTestResult result, String path) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), path);
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
