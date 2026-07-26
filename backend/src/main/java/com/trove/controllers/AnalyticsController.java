/*
 * ============================================================================
 *  AnalyticsController — spend-tracking endpoints
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Read-only spend endpoints: by category, by month, and a summary — scoped to a
 *  space and optional date range.
 *
 *  Business use case
 *  -----------------
 *  Lets a client render "spend by category" and a monthly trend. Foundation for the
 *  later anomaly alerts.
 *
 *  Solution architecture
 *  ---------------------
 *  Base path /api/spend (authenticated). Acting user from CurrentUser; space
 *  defaults to the caller's personal space; membership enforced in AnalyticsService.
 *  Dates are ISO (yyyy-MM-dd) and optional.
 * ============================================================================
 */
package com.trove.controllers;
import com.trove.service.impl.AnalyticsService;

import com.trove.security.CurrentUser;
import com.trove.service.impl.SpaceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/spend")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;

    public AnalyticsController(AnalyticsService analyticsService, SpaceService spaceService,
                              CurrentUser currentUser) {
        this.analyticsService = analyticsService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
    }

    /** Spend grouped by category. */
    @GetMapping("/by-category")
    public List<AnalyticsService.CategorySpendResponse> byCategory(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "currency", required = false, defaultValue = "INR") String currency) {
        return analyticsService.byCategory(resolveSpace(spaceId), currentUser.requireUserId(), from, to, currency);
    }

    /** Spend grouped by month (YYYY-MM). */
    @GetMapping("/by-month")
    public List<AnalyticsService.MonthlySpendResponse> byMonth(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "currency", required = false, defaultValue = "INR") String currency,
            @RequestParam(value = "granularity", required = false, defaultValue = "month") String granularity) {
        return analyticsService.byMonth(resolveSpace(spaceId), currentUser.requireUserId(), from, to, currency, granularity);
    }

    /** Overall total + per-category breakdown. */
    @GetMapping("/summary")
    public AnalyticsService.SpendSummary summary(
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "currency", required = false, defaultValue = "INR") String currency) {
        return analyticsService.summary(resolveSpace(spaceId), currentUser.requireUserId(), from, to, currency);
    }

    private UUID resolveSpace(UUID spaceId) {
        return spaceId != null ? spaceId : spaceService.personalSpaceId(currentUser.requireUserId());
    }
}
