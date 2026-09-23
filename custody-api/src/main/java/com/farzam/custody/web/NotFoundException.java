package com.farzam.custody.web;

import java.io.Serial;
import java.util.UUID;

/**
 * The resource named in the path does not exist.
 *
 * <p>Thrown by controllers, not by services, and that split is the point. "There is no account with
 * this id" means two different things in two different places: as the target of
 * {@code GET /v1/accounts/{id}} it is a {@code 404}, and as a field inside the body of
 * {@code POST /v1/withdrawals} it is a {@code 422}, because the withdrawal endpoint exists and the
 * request is simply not one that can be carried out. Only the controller knows which of the two it
 * is looking at, so the services return an {@link java.util.Optional} and let it decide.
 *
 * @see com.farzam.custody.ledger.UnknownAccountException the 422 half of the same fact
 */
public class NotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    private NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static NotFoundException account(UUID id) {
        return new NotFoundException("ACCOUNT_NOT_FOUND", "no account with id " + id);
    }

    public static NotFoundException withdrawal(UUID id) {
        return new NotFoundException("WITHDRAWAL_NOT_FOUND", "no withdrawal with id " + id);
    }

    /** The machine-readable code for the {@code code} member of the problem document. */
    public String code() {
        return code;
    }
}
