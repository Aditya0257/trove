package com.trove.service;

import com.trove.dto.Hit;
import java.util.List;
import java.util.UUID;

/** Service contract for EmbeddingService. */
public interface EmbeddingService {
    String model();
    boolean index(UUID documentId, UUID billToUserId);
    List<Hit> search(UUID spaceId, String queryText, UUID billToUserId, int k);
    List<UUID> staleDocumentIds(UUID spaceId, int limit);
    List<UUID> staleDocumentIds(int limit);
}
