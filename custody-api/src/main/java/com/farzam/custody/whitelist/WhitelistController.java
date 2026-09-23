package com.farzam.custody.whitelist;

import com.farzam.custody.api.ClientsApi;
import com.farzam.custody.api.model.WhitelistEntryDto;
import com.farzam.custody.api.model.WhitelistRequestDto;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/clients/{clientId}/whitelist}.
 *
 * <p>{@code 201} even when the address was already listed. The alternative — {@code 200} for a
 * repeat, {@code 201} for a first — makes the status code report on server history rather than on
 * the outcome, and gives a retrying client two different answers to the same question. The state the
 * client asked for holds either way, which is what the response should say.
 */
@RestController
class WhitelistController implements ClientsApi {

    private final WhitelistService whitelist;

    WhitelistController(WhitelistService whitelist) {
        this.whitelist = whitelist;
    }

    @Override
    public ResponseEntity<WhitelistEntryDto> whitelistAddress(UUID clientId, WhitelistRequestDto request) {
        WhitelistedAddress entry = whitelist.allow(clientId, request.getAddress());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WhitelistEntryDto(entry.getClientId(), entry.getAddress()));
    }
}
