/*
 * ============================================================================
 *  IngestTokenService — mint, rotate, and resolve per-space ingest tokens
 * ============================================================================
 *  Purpose:        generate a space's unguessable token, rotate it, resolve a token
 *                  back to its space, and render the space's ingest address.
 *  Business use:    powers per-space forward-to-file addresses.
 *  Design:         tokens are 24 random bytes, URL-safe base64 (no padding). One per
 *                  space (upsert on space_id).
 * ============================================================================
 */
package com.trove.ingestion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestTokenService {

    private final IngestTokenRepository repository;
    private final IngestProperties props;
    private final SecureRandom random = new SecureRandom();

    public IngestTokenService(IngestTokenRepository repository, IngestProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** Returns the space's token, creating one on first request. */
    @Transactional
    public IngestToken getOrCreate(UUID spaceId) {
        return repository.findById(spaceId)
                .orElseGet(() -> repository.save(new IngestToken(spaceId, generate())));
    }

    /** Issues a fresh token for the space (invalidates the old one). */
    @Transactional
    public IngestToken rotate(UUID spaceId) {
        IngestToken t = repository.findById(spaceId).orElse(new IngestToken(spaceId, generate()));
        t.setToken(generate());
        return repository.save(t);
    }

    /** Resolves a token to its space id, if valid. */
    @Transactional(readOnly = true)
    public Optional<UUID> findSpaceId(String token) {
        return repository.findByToken(token).map(IngestToken::getSpaceId);
    }

    /** The full ingest address a user forwards documents to. */
    public String address(String token) {
        return "trove+" + token + "@" + props.getAddressDomain();
    }

    private String generate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
