package com.farzam.signer.signing;

import java.io.Serial;

/**
 * The signer has decided not to sign, and the decision is final.
 *
 * <p>The distinction this type draws is the one the whole error-handling design turns on. An
 * unreachable node or a database that will not answer is a <em>failure</em>: it is retried, and if it
 * keeps happening the message is dead-lettered for a human. A refusal is not a failure — it is this
 * service working correctly — and retrying it three times would produce the same answer three times
 * before losing it in a topic nobody reads, leaving the withdrawal APPROVED and the client's money
 * held for ever.
 *
 * <p>So a refusal is never thrown out of the listener. It is caught, turned into a
 * {@code WithdrawalSigningFailed} event and committed, which is what lets custody-api put the funds
 * back. Saying no out loud is the point.
 *
 * <p>{@link #getMessage()} is written to be shown to a human reading a withdrawal's history, and it
 * travels across a service boundary to get there. It says what this service decided; it never
 * quotes anything the requester supplied, and it never says which of several checks failed in more
 * detail than an operator needs — "no valid approval from a trusted approver" is useful, "approver
 * 3f2a is not in the trusted list" tells whoever is probing which ids exist.
 */
public class SigningRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param reason why the signer said no, in words fit for a client-facing history
     */
    public SigningRefusedException(String reason) {
        super(reason);
    }
}
