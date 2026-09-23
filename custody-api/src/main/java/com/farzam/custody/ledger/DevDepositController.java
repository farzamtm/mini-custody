package com.farzam.custody.ledger;

import com.farzam.custody.api.DevApi;
import com.farzam.custody.api.model.AccountDto;
import com.farzam.custody.api.model.DepositRequestDto;
import java.math.BigInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /dev/deposits} — an endpoint that creates money out of nothing.
 *
 * <p>{@code @Profile("dev")} is the whole safety story, and it is worth being precise about why it is
 * enough. A profile is not a feature flag checked at request time: without it the bean is never
 * created, so the handler is never registered, so the path does not exist and returns a {@code 404}
 * like any other unmapped URL. There is no code path from a production deployment to this class
 * short of starting it with the wrong profile.
 *
 * <p>It is still the most dangerous file in the repository, which is why it is one screen long and
 * does nothing but delegate.
 */
@RestController
@Profile("dev")
class DevDepositController implements DevApi {

    private final DepositService deposits;

    DevDepositController(DepositService deposits) {
        this.deposits = deposits;
    }

    @Override
    public ResponseEntity<AccountDto> simulateDeposit(DepositRequestDto request) {
        // new BigInteger(String) rather than a JSON number: see the contract's note on why every
        // amount crosses the wire as a string. The pattern in the schema has already established
        // that this is a positive decimal integer, so the parse cannot throw.
        Account account = deposits.deposit(request.getClientId(), new BigInteger(request.getAmountWei()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountDtos.of(account));
    }
}
