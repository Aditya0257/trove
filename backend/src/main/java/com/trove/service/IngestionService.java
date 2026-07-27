package com.trove.service;

import com.trove.dto.DocumentResponse;
import java.util.UUID;

/** Service contract for IngestionService. */
public interface IngestionService {
    void checkAuthorized(String token);
    UUID resolveSpace(String token, UUID explicitSpaceId);
    DocumentResponse ingest(UUID spaceId, String filename, String contentType, byte[] bytes, String source);
}
