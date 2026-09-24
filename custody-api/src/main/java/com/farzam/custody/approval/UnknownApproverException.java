package com.farzam.custody.approval;

import java.io.Serial;
import java.util.UUID;

/**
 * The approval names somebody this service has no key for.
 *
 * <p>A {@code 422} rather than a {@code 404}. The endpoint being addressed is the withdrawal's
 * approvals collection, which exists; the approver is a field in the body, and a body naming
 * something that does not exist is a request that cannot be carried out rather than a resource that
 * is missing. The same distinction {@link com.farzam.custody.web.NotFoundException} draws for
 * accounts.
 */
public class UnknownApproverException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param approverId the id that is not in the registry
     */
    public UnknownApproverException(UUID approverId) {
        super("no approver with id " + approverId);
    }
}
