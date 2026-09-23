package com.farzam.custody.ledger;

import com.farzam.custody.api.model.AccountDto;

/**
 * Turns an {@link Account} into what the contract promises.
 *
 * <p>A hand-written four-line mapper rather than MapStruct or a reflective mapper. The two types
 * differ in exactly the places where the difference is the interesting part — the balance is a
 * {@link java.math.BigInteger} in the domain and a decimal string on the wire, because a JSON number
 * is a double in most parsers and 10^18 wei does not fit in one — and a mapper that made those
 * conversions implicit would be hiding the only thing here worth reading.
 */
final class AccountDtos {

    private AccountDtos() {}

    static AccountDto of(Account account) {
        return new AccountDto(
                account.getId(),
                AccountDto.TypeEnum.fromValue(account.getType().name()),
                account.getAsset(),
                account.getBalance().toString()).clientId(account.getClientId());
    }
}
