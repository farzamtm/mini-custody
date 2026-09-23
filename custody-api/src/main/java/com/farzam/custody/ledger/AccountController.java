package com.farzam.custody.ledger;

import com.farzam.custody.api.AccountsApi;
import com.farzam.custody.api.model.AccountDto;
import com.farzam.custody.web.NotFoundException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/accounts/{id}}.
 *
 * <p>Implements the generated {@link AccountsApi}, which carries the path, the verb and the response
 * type from {@code openapi.yaml}. There is no {@code @GetMapping} in this file, and that is the
 * point of generating the interface: the contract cannot drift from the code, because changing the
 * contract changes the interface and the code stops compiling.
 *
 * <p>Reads the entity rather than {@link LedgerService#balanceOf}, because the response carries the
 * type and the owner as well as the number, and one row read answers all three.
 */
@RestController
class AccountController implements AccountsApi {

    private final AccountRepository accounts;

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<AccountDto> getAccount(UUID accountId) {
        Account account = accounts.findById(accountId).orElseThrow(() -> NotFoundException.account(accountId));
        return ResponseEntity.ok(AccountDtos.of(account));
    }
}
