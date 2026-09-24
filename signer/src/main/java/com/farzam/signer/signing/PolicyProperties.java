package com.farzam.signer.signing;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limits the signer applies to everything it is asked to sign.
 *
 * <p><b>These live here and not in custody-api, and the duplication is the security model.</b>
 * custody-api enforces the same quorum before it publishes anything, from
 * {@code ApprovalProperties} and an {@code approvers} table it can add to at runtime. If the signer
 * trusted it to have done so, then compromising custody-api would be enough to move funds. The
 * signer re-checks from its own configuration, against its own list of public keys, so an attacker
 * who owns the API completely can put whatever they like in an event and still needs a private key
 * they have never had. ADR 0010 covers the asymmetry and what happens when the two thresholds
 * disagree.
 *
 * @param maxTransactionWei the hot-wallet cap: the most this service will sign in one transaction,
 *     whatever the approvals say. A hot wallet is online and automated by definition, so the amount
 *     it can move without a human is the amount an attacker gets for free. Anything larger is a
 *     warm-wallet operation with a person in the loop.
 * @param secondApprovalFromWei the four-eyes threshold. At or above this, two distinct approvers are
 *     required; below it, one. The point is not that small withdrawals are safe — it is that a rule
 *     expensive enough to be routinely bypassed protects nothing, so the expensive rule is reserved
 *     for the amounts that justify it.
 * @param trustedApprovers every approver this service will accept a signature from. An empty list is
 *     valid and means the signer refuses everything, which is the right default for a list that must
 *     never be derived from custody-api's.
 */
@ConfigurationProperties("signer.policy")
public record PolicyProperties(BigInteger maxTransactionWei, BigInteger secondApprovalFromWei,
        List<TrustedApprover> trustedApprovers) {

    /**
     * Defaults chosen so that a misconfiguration fails closed.
     *
     * <p>A missing cap becomes zero rather than unlimited, and a missing approver list becomes empty
     * rather than "anyone". Both make the signer refuse every withdrawal, which is loud, immediate
     * and harmless; the alternative reading of an absent property is a signer that quietly signs
     * anything for anybody.
     */
    public PolicyProperties {
        maxTransactionWei = maxTransactionWei == null ? BigInteger.ZERO : maxTransactionWei;
        secondApprovalFromWei = secondApprovalFromWei == null ? BigInteger.ZERO : secondApprovalFromWei;
        trustedApprovers = trustedApprovers == null ? List.of() : List.copyOf(trustedApprovers);
    }

    /**
     * One approver the signer will listen to.
     *
     * <p>The key is configuration rather than data: it is deployed with the service and changing it
     * is a deployment, not an API call. That is deliberate — an approver list a running system can
     * edit is an approver list an attacker can edit.
     *
     * @param id the approver, matching the {@code approverId} on an approval
     * @param publicKey their Ed25519 public key, 32 bytes base64
     */
    public record TrustedApprover(UUID id, String publicKey) {}
}
