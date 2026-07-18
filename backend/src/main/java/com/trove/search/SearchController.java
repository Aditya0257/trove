/*
 * ============================================================================
 *  SearchController — natural-language and structured search endpoints
 * ============================================================================
 *  Purpose:        GET /api/search?q=... (natural language) and
 *                  GET /api/search/structured?... (explicit filters).
 *  Business use:    the search box behind "my last water bill" and advanced filters.
 *  Design:         authenticated; space defaults to the caller's personal space;
 *                  membership enforced in SearchService. The natural endpoint returns
 *                  the interpreted filters so the client can show "showing: …".
 * ============================================================================
 */
package com.trove.search;

import com.trove.common.security.CurrentUser;
import com.trove.document.dto.DocumentResponse;
import com.trove.space.SpaceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public SearchController(SearchService searchService, SpaceService spaceService,
                           CurrentUser currentUser) {
        this.searchService = searchService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** Natural-language search: "my last water bill", "all Nike purchases", … */
    @GetMapping
    public SearchService.SearchResult natural(
            @RequestParam("q") String q,
            @RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        return searchService.naturalSearch(resolveSpace(spaceId, user), user, q);
    }

    /** Structured search with explicit filters. */
    @GetMapping("/structured")
    public List<DocumentResponse> structured(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "min", required = false) BigDecimal min,
            @RequestParam(value = "max", required = false) BigDecimal max,
            @RequestParam(value = "limit", required = false) Integer limit) {
        UUID user = currentUser.requireUserId();
        SearchQuery q = new SearchQuery();
        q.setCategoryCode(category);
        q.setText(text);
        q.setStatus(status);
        q.setDateFrom(from);
        q.setDateTo(to);
        q.setAmountMin(min);
        q.setAmountMax(max);
        q.setLimit(limit);
        return searchService.search(resolveSpace(spaceId, user), user, q);
    }

    private UUID resolveSpace(UUID spaceId, UUID user) {
        return spaceId != null ? spaceId : spaceService.personalSpaceId(user);
    }
}
