package com.farzam.signer.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.farzam.signer.chain.ChainProperties;
import com.farzam.signer.chain.EthereumRpc;
import com.farzam.signer.crypto.HotWallet;
import com.farzam.signer.crypto.WalletKeys;
import com.farzam.signer.support.AbstractSignerTest;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

/**
 * Recovering from a broadcast that did not happen.
 *
 * <p>The scenario is a signature that committed and bytes that never reached the network — the
 * process died between the two, or the node was down for a minute. The row sits in
 * {@code signing_log} with a null {@code broadcast_at}, and the fix is to send the identical bytes
 * again rather than to sign anything.
 *
 * <p>The state is arranged directly rather than by killing a process mid-flight, because the thing
 * worth testing is the recovery, and a test that can only reach the state by being lucky about
 * timing is a test that will be quarantined within a month.
 *
 * <p><b>Each test signs from its own wallet, and that is not tidiness.</b> Written against the
 * shared hot wallet, these tests broke every other test in the module, and they were right to: a
 * signed transaction that is deliberately not sent leaves a gap in that wallet's nonce sequence, and
 * nothing behind a gap can be mined until it is filled. Every later withdrawal sat in the mempool
 * and every balance assertion in the suite timed out. That is precisely the hazard {@link Nonces}
 * documents, reproduced accidentally, and the fix in a test is the same as the fix in production:
 * do not let one wallet's sequence be two parties' business.
 *
 * <p>The {@link Broadcaster} here is constructed with a zero retry-after so the backlog is visible
 * immediately. The configured ten seconds exists to keep the scheduled job from racing the
 * listener's own broadcast, and waiting for it would be ten seconds of nothing.
 */
@SpringBootTest
class BroadcastRetryTest extends AbstractSignerTest {

    private static final BigInteger AMOUNT = new BigInteger("100000000000000000"); // 0.1 ETH

    private static final BigInteger FUNDING = BigInteger.TEN.pow(19); // 10 ETH

    @Autowired
    private EthereumRpc chainRpc;

    @Autowired
    private ChainProperties chainProperties;

    @Autowired
    private SigningLog signingLog;

    @Autowired
    private Nonces nonces;

    @Autowired
    private WalletKeys walletKeys;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aTransactionSignedButNeverSentIsResentAndArrives() {
        String destination = freshAddress();
        SignedTransaction signed = signWithoutBroadcasting(destination);

        assertThat(chain().balanceOf(destination)).isZero();
        assertThat(signingLog.unbroadcast(Duration.ZERO, 50)).extracting(SignedTransaction::withdrawalId)
                .contains(signed.withdrawalId());

        Broadcaster broadcaster = new Broadcaster(chainRpc, signingLog, Duration.ZERO);
        assertThat(broadcaster.resendBacklog()).isPositive();

        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(chain().balanceOf(destination)).isEqualTo(AMOUNT));

        // And the row leaves the backlog, so the job does not resend it for ever.
        assertThat(signingLog.unbroadcast(Duration.ZERO, 50)).extracting(SignedTransaction::withdrawalId)
                .doesNotContain(signed.withdrawalId());
    }

    /**
     * The property the whole retry design rests on: identical bytes sent twice are one transaction,
     * and a node that already has them is the resend succeeding rather than failing.
     */
    @Test
    void sendingTheSameSignedTransactionAgainDoesNotPayTwice() {
        String destination = freshAddress();
        SignedTransaction signed = signWithoutBroadcasting(destination);

        chainRpc.sendRawTransaction(signed.rawTransaction(), signed.txHash());
        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(chain().balanceOf(destination)).isEqualTo(AMOUNT));

        // Once mined, the same bytes come back as "nonce too low" rather than "already known", and
        // the two are indistinguishable from the message alone. The Broadcaster settles it by
        // looking for a receipt: a transaction the chain already has is a resend that succeeded.
        Broadcaster broadcaster = new Broadcaster(chainRpc, signingLog, Duration.ZERO);
        assertThat(broadcaster.resendBacklog()).isNotNegative();

        assertThat(signingLog.unbroadcast(Duration.ZERO, 50)).extracting(SignedTransaction::withdrawalId)
                .doesNotContain(signed.withdrawalId());
        // Once, not twice. A second acceptance would be a second payment.
        assertThat(chain().balanceOf(destination)).isEqualTo(AMOUNT);
    }

    /**
     * The timer is a separate bean so tests can switch it off, which means nothing would otherwise
     * ever construct it. One tick proves the wiring is real — that the scheduled method reaches
     * {@link Broadcaster#resendBacklog()} rather than, say, a method on the wrong object.
     */
    @Test
    void theScheduledTickDrivesTheRetryJob() {
        String destination = freshAddress();
        SignedTransaction signed = signWithoutBroadcasting(destination);

        new BroadcastScheduling(new Broadcaster(chainRpc, signingLog, Duration.ZERO)).tick();

        await().atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(chain().balanceOf(destination)).isEqualTo(AMOUNT));
        assertThat(signingLog.unbroadcast(Duration.ZERO, 50)).extracting(SignedTransaction::withdrawalId)
                .doesNotContain(signed.withdrawalId());
    }

    /**
     * Signs and records, and deliberately does not send — the state a crash between the commit and
     * the broadcast leaves behind.
     */
    private SignedTransaction signWithoutBroadcasting(String destination) {
        String wallet = fundedWallet();
        var signer = new TransactionSigner(walletKeys, chainProperties, new HotWallet(wallet, ""));
        UUID withdrawalId = UUID.randomUUID();

        return new TransactionTemplate(transactionManager).execute(status -> {
            long nonce = nonces.reserve(wallet);
            SignedTransaction signed = signer.sign(withdrawalId, destination, AMOUNT, nonce, chainRpc.currentFees());
            signingLog.record(signed);
            return signed;
        });
    }

    /** A wallet of this test's own, with a balance and a key the signer can unseal. */
    private String fundedWallet() {
        ECKeyPair pair;
        try {
            pair = Keys.createEcKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
        String address = "0x" + Keys.getAddress(pair);
        chain().setBalance(address, FUNDING);
        walletKeys.store(address, Numeric.toBytesPadded(pair.getPrivateKey(), 32));
        return address;
    }
}
