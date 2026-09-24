package com.farzam.signer.signing;

import com.farzam.events.ApprovalStatement;
import com.farzam.events.WithdrawalApproved;
import com.farzam.signer.crypto.Ed25519;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Everything that has to be true before a private key is touched.
 *
 * <p>This class is the reason the signer is a separate service. An attacker who owns custody-api
 * completely can write any row into its database and publish any event they like — including a
 * {@code WithdrawalApproved} for ten thousand ETH to an address they control, with a perfectly
 * well-formed list of approvals in it. Every check below is applied to that event as though it were
 * hostile, because in the scenario worth defending against it is.
 *
 * <p><b>The signatures are verified against this service's own keys, not the ones in the event.</b>
 * That single line is the whole argument. {@code WithdrawalApproved} carries a {@code publicKey}
 * with each approval, and using it would be verifying a forger's signature against the forger's own
 * key — arithmetic that always succeeds and proves nothing. The {@code approverId} selects a key
 * from {@link PolicyProperties#trustedApprovers()}, which arrived with the deployment, and if there
 * is no such approver the approval is worth nothing no matter how valid its signature is.
 *
 * <p><b>What is signed is not the event.</b> Approvers sign an {@link ApprovalStatement} — the
 * withdrawal, the destination, the amount — reconstructed here from the event as it arrived. So an
 * attacker who takes a genuine approved event and edits the destination has produced a statement
 * nobody signed, and every signature on it stops verifying.
 */
@Component
public final class SigningPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(SigningPolicy.class);

    /** 20 bytes of hex. Case-insensitive here; folded to lower case before it is signed. */
    private static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private final PolicyProperties properties;

    /**
     * The trusted keys, parsed once at startup.
     *
     * <p>Parsed here rather than per verification, so that a key of the wrong length or the wrong
     * encoding stops the service from starting instead of turning into a refusal at three in the
     * morning, blamed on the approver whose key it is.
     *
     * <p>That catches less than it sounds like, and the gap is documented rather than papered over:
     * the JDK's {@code KeyFactory} will build a key from 32 bytes without checking that they
     * describe a point on the curve. A garbled-but-correctly-sized key therefore survives startup,
     * and shows up as that approver's signatures never verifying — a refusal rather than a wrongly
     * accepted withdrawal, which is the right way for it to fail.
     */
    private final Map<UUID, PublicKey> trusted;

    SigningPolicy(PolicyProperties properties) {
        this.properties = properties;
        this.trusted = parse(properties);
        if (trusted.isEmpty()) {
            // Not an error — it is the correct state until M3 can produce signed approvals — but it
            // means every withdrawal will be refused, and that should be visible on startup rather
            // than discovered one refusal at a time.
            LOG.warn("no trusted approvers are configured; every withdrawal will be refused");
        }
    }

    /**
     * Decides whether this withdrawal may be signed.
     *
     * <p>Returns nothing. There is no "how sure are we" here: either every condition holds and the
     * caller proceeds, or one does not and this throws.
     *
     * @param event the approval, treated as hostile input
     * @throws SigningRefusedException with a reason fit to show a client
     */
    public void check(WithdrawalApproved event) {
        if (!ADDRESS.matcher(event.destination()).matches()) {
            throw new SigningRefusedException("the destination is not a well-formed Ethereum address");
        }
        if (event.amountWei().signum() <= 0) {
            throw new SigningRefusedException("the amount is not positive");
        }
        if (event.amountWei().compareTo(properties.maxTransactionWei()) > 0) {
            // The limit is named, the amount is not. An operator reading this already has the
            // withdrawal in front of them; anybody probing for the cap can read it here either way,
            // and knowing it does not help them beat it.
            throw new SigningRefusedException(
                    "the amount is above this wallet's per-transaction limit of " + properties.maxTransactionWei()
                            + " wei");
        }

        int required = requiredApprovals(event.amountWei());
        int valid = countValidApprovals(event);
        if (valid < required) {
            throw new SigningRefusedException(
                    "this withdrawal needs " + required + " valid approvals from trusted approvers and has " + valid);
        }
    }

    /**
     * How many approvers this amount needs.
     *
     * @param amountWei the withdrawal amount
     * @return two at or above the threshold, one below it
     */
    int requiredApprovals(BigInteger amountWei) {
        return amountWei.compareTo(properties.secondApprovalFromWei()) >= 0 ? 2 : 1;
    }

    /**
     * Counts approvals that are both from someone this service trusts and genuinely theirs.
     *
     * <p><b>Distinct approvers, not approvals.</b> Counting rows would let one approver send the
     * same valid signature twice and satisfy a two-approver quorum on their own, which defeats the
     * entire point of four-eyes. The set is of ids, and an id only gets in once.
     *
     * <p>Each failure is logged at debug and not above. A refusal produces one event saying what was
     * decided; a stream of warnings naming approver ids on every unsigned event from M2-era
     * custody-api would be noise that trains people to ignore the log.
     */
    @SuppressFBWarnings(
            value = "CRLF_INJECTION_LOGS",
            justification = "The only value logged is an approver id, which Jackson has already "
                    + "parsed as a UUID. A UUID cannot contain a newline.")
    private int countValidApprovals(WithdrawalApproved event) {
        byte[] statement = ApprovalStatement.of(event).canonicalBytes();
        Set<UUID> approvers = new HashSet<>();

        for (WithdrawalApproved.Approval approval : event.approvals()) {
            PublicKey key = trusted.get(approval.approverId());
            if (key == null) {
                LOG.debug("an approval names {}, who is not a trusted approver", approval.approverId());
                continue;
            }
            byte[] signature = decode(approval.signature());
            if (signature.length == 0 || !Ed25519.verify(key, statement, signature)) {
                LOG.debug("the signature from {} does not verify against this withdrawal", approval.approverId());
                continue;
            }
            approvers.add(approval.approverId());
        }
        return approvers.size();
    }

    /**
     * Base64 that may well be nonsense, because it came off a topic.
     *
     * @return the bytes, or an empty array, which never verifies
     */
    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException malformed) {
            return new byte[0];
        }
    }

    private static Map<UUID, PublicKey> parse(PolicyProperties properties) {
        Map<UUID, PublicKey> keys = new HashMap<>();
        for (PolicyProperties.TrustedApprover approver : properties.trustedApprovers()) {
            try {
                keys.put(approver.id(), Ed25519.publicKeyFrom(Base64.getDecoder().decode(approver.publicKey())));
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(
                        "the configured public key for approver " + approver.id() + " is not a valid Ed25519 key",
                        malformed);
            }
        }
        return Map.copyOf(keys);
    }
}
