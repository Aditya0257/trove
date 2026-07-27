package com.trove.service;

import com.trove.dto.SearchResult;
import com.trove.dto.SearchQuery;
import com.trove.dto.DocumentResponse;
import java.util.List;
import java.util.UUID;

/** Service contract for SearchService. */
public interface SearchService {
    SearchResult naturalSearch(UUID spaceId, UUID userId, String queryText);
    List<DocumentResponse> search(UUID spaceId, UUID userId, SearchQuery q);
}
