package com.trove.service;

import com.trove.entity.IngestToken;
import java.util.Optional;
import java.util.UUID;

/** Service contract for IngestTokenService. */
public interface IngestTokenService {
    IngestToken getOrCreate(UUID spaceId);
    IngestToken rotate(UUID spaceId);
    Optional<UUID> findSpaceId(String token);
    String address(String token);
}
