/*
 * ============================================================================
 *  InsightsController — document intelligence endpoints
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Read-only "document intelligence" for a space: an "expiring soon" list and a
 *  recurring/subscription view. Both are derived from confirmed documents (no extra
 *  storage, no AI cost).
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/insights (authenticated). Acting user from CurrentUser; space
 *  defaults to the caller's personal space; membership enforced in InsightsService.
 * ============================================================================
 */
package com.trove.insights;

import com.trove.common.security.CurrentUser;
import com.trove.space.SpaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public InsightsController(InsightsService insightsService, SpaceService spaceService,
                             CurrentUser currentUser) {
        this.insightsService = insightsService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** Bills due, warranties ending and renewals within the window (default 90 days). */
    @GetMapping("/expiring")
    public List<InsightsService.ExpiringItem> expiring(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "withinDays", required = false, defaultValue = "90") int withinDays) {
        return insightsService.expiring(resolveSpace(spaceId), currentUser.requireUserId(), withinDays);
    }

    /** Merchant+category groups that repeat on a regular cadence, with the next predicted date. */
    @GetMapping("/recurring")
    public List<InsightsService.RecurringGroup> recurring(
            @RequestParam(value = "spaceId", required = false) UUID spaceId) {
        return insightsService.recurring(resolveSpace(spaceId), currentUser.requireUserId());
    }

    private UUID resolveSpace(UUID spaceId) {
        return spaceId != null ? spaceId : spaceService.personalSpaceId(currentUser.requireUserId());
    }
}
