/*
 * ============================================================================
 *  IngestTokenRepository — data access for per-space ingest tokens
 * ============================================================================
 *  Purpose:        resolve a token to its space, and fetch/rotate a space's token.
 * ============================================================================
 */
package com.trove.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IngestTokenRepository extends JpaRepository<IngestToken, UUID> {

    Optional<IngestToken> findByToken(String token);
}
