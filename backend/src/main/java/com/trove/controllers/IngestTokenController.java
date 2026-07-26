/*
 * ============================================================================
 *  IngestTokenController — view/rotate a space's forward-to-file address
 * ============================================================================
 *  Purpose:        owner endpoints to get (creating on first call) and rotate a
 *                  space's ingest token, and see the address to forward documents to.
 *  Business use:    "forward your bills to THIS address and they file into this space."
 *  Design:         owner-gated (SpaceAuthorization). The token is a routing secret —
 *                  rotating it invalidates the old address.
 * ============================================================================
 */
package com.trove.controllers;
import com.trove.entity.IngestToken;
import com.trove.service.impl.IngestTokenService;

import com.trove.security.CurrentUser;
import com.trove.security.SpaceAuthorization;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/spaces/{spaceId}/ingest-address")
public class IngestTokenController {

    private final IngestTokenService ingestTokenService;
    private final SpaceAuthorization authorization;
    private final CurrentUser currentUser;

    public IngestTokenController(IngestTokenService ingestTokenService,
                                SpaceAuthorization authorization, CurrentUser currentUser) {
        this.ingestTokenService = ingestTokenService;
        this.authorization = authorization;
        this.currentUser = currentUser;
    }

    /** The space's ingest address (creates the token on first request). Owner only. */
    @GetMapping
    public AddressResponse get(@PathVariable UUID spaceId) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        IngestToken t = ingestTokenService.getOrCreate(spaceId);
        return new AddressResponse(t.getToken(), ingestTokenService.address(t.getToken()));
    }

    /** Rotate the token (invalidates the old address). Owner only. */
    @PostMapping("/rotate")
    public AddressResponse rotate(@PathVariable UUID spaceId) {
        authorization.requireOwner(spaceId, currentUser.requireUserId());
        IngestToken t = ingestTokenService.rotate(spaceId);
        return new AddressResponse(t.getToken(), ingestTokenService.address(t.getToken()));
    }

    public record AddressResponse(String token, String address) {
    }
}
