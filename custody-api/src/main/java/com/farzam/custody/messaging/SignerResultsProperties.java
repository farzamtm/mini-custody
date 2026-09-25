package com.farzam.custody.messaging;

import com.farzam.crypto.Ed25519;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key custody-api checks signer results against.
 *
 * <p><b>This is the counterpart to {@code signer.policy.trusted-approvers}, pointing the other
 * way.</b> The signer keeps its own list of approver keys so that owning custody-api is not enough
 * to make it sign. Until M7 nothing did the reverse, and the gap was not symmetric bookkeeping — it
 * was exploitable. A {@code WithdrawalSigningFailed} on the results topic releases a client's ledger
 * hold, so anyone able to produce to that topic could have the money credited back while the real
 * signer went on to broadcast the transaction. ADR 0012 has the argument and the exploit.
 *
 * <p>The key is configuration rather than data, for the same reason the approver list is: a key a
 * running system can edit is a key an attacker can edit. It arrives with the deployment.
 *
 * @param publicKey the signer's Ed25519 public key, 32 bytes base64. Absent means this service
 *     trusts no signer and rejects every result — see below.
 */
@ConfigurationProperties("custody.signer-results")
public record SignerResultsProperties(String publicKey) {

    /**
     * An absent key means "trust nobody", not "trust anybody".
     *
     * <p>Fail-closed, and the consequence is worth stating plainly because it is not free: a
     * deployment that forgets this property has every signer result dead-lettered, so withdrawals
     * reach {@code APPROVED}, get signed and broadcast on chain, and then never progress. That is
     * bad. The alternative default is worse by a different order of magnitude — a service that
     * accepts unauthenticated instructions to release holds — and unlike this one it fails silently
     * and in the attacker's favour. The noisy failure is the one to choose.
     */
    public SignerResultsProperties {
        publicKey = publicKey == null || publicKey.isBlank() ? null : publicKey.trim();
    }

    /**
     * Parses the configured key once, at startup.
     *
     * <p>Eager rather than per-message: a malformed key should stop the service rather than turn
     * into a verification failure on every result, which would look like an attack in the logs and
     * send perfectly good messages to the dead-letter topic.
     *
     * @return the key, or empty if none is configured
     * @throws IllegalArgumentException if the property is set but is not a base64 Ed25519 key
     */
    public Optional<PublicKey> signerKey() {
        if (publicKey == null) {
            return Optional.empty();
        }
        return Optional.of(Ed25519.publicKeyFrom(Base64.getDecoder().decode(publicKey)));
    }
}
