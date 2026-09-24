package com.farzam.custody.approval;

import java.io.Serial;
import java.util.UUID;

/**
 * The approver acts for the client whose money this is.
 *
 * <p>Four eyes means two independent people, not two key pairs. An approver registered against a
 * client is that client, as far as this control is concerned, so letting them approve their own
 * withdrawal would leave the whole scheme satisfied by one party holding two keys — which is a
 * configuration mistake somebody makes on their first afternoon, and which no amount of signature
 * verification would catch.
 *
 * <p>Custodian staff — an approver with no client — are independent of everyone and never land here.
 */
public class SelfApprovalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param approverId who tried
     * @param clientId the client they act for, which is also the withdrawal's client
     */
    public SelfApprovalException(UUID approverId, UUID clientId) {
        super("approver " + approverId + " acts for client " + clientId + " and cannot approve their withdrawals");
    }
}
