package com.farzam.custody.approval;

import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds somebody to the approver registry.
 *
 * <p>One method, and it is behind a dev-profile endpoint, so the obvious question is why it is a
 * service at all rather than four lines in the controller. Because the controller is the part that
 * will not survive: in a real deployment approvers arrive through an onboarding process, not an HTTP
 * call, and whatever replaces {@code DevApproverController} still has to decode a key, reject a
 * malformed one and write the row. Putting that here means the replacement is a new caller rather
 * than a rewrite.
 */
@Service
public class ApproverService {

    private final ApproverRepository approvers;

    ApproverService(ApproverRepository approvers) {
        this.approvers = approvers;
    }

    /**
     * Registers an approver.
     *
     * <p>The key is rejected here if it is not a well-formed Ed25519 public key, rather than at the
     * first approval. The difference matters: at registration there is somebody watching who can fix
     * the paste, and at verification time the only available answer is "that signature did not
     * verify", which points at the approver instead of at the key that was typed in wrong.
     *
     * @param name who they are
     * @param publicKeyBase64 their raw 32-byte Ed25519 public key, base64
     * @param clientId the client they act for, or null for custodian staff
     * @return the registered approver
     * @throws MalformedPublicKeyException if the key is not base64, or not a well-formed Ed25519 key
     */
    @Transactional
    public Approver register(String name, String publicKeyBase64, UUID clientId) {
        byte[] publicKey;
        try {
            publicKey = Base64.getDecoder().decode(publicKeyBase64);
        } catch (IllegalArgumentException malformed) {
            throw new MalformedPublicKeyException("the public key is not valid base64", malformed);
        }

        try {
            return approvers.save(Approver.register(name, publicKey, clientId));
        } catch (IllegalArgumentException malformed) {
            // Approver.register parses the key and lets Ed25519 complain. Translated here so the
            // web layer has one exception type to map, rather than an IllegalArgumentException that
            // could have come from anywhere and would default to a 500.
            throw new MalformedPublicKeyException(malformed.getMessage(), malformed);
        }
    }
}
