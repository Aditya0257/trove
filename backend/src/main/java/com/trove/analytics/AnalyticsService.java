/*
 * ============================================================================
 *  AnalyticsService — spend tracking and category history
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Computes spend grouped by category and by month for a space over a date range,
 *  plus a summary, from confirmed documents only.
 *
 *  Business use case
 *  -----------------
 *  "Where does my money go" and "how does this month compare" — the spend-tracking
 *  value in the brief, and the data the later anomaly detector will build on.
 *
 *  Solution architecture
 *  ---------------------
 *  Authorizes the caller against the space (any member may read), substitutes wide
 *  date defaults for nulls, and delegates aggregation to AnalyticsRepository.
 *
 *  Reasoning & logic
 *  -----------------
 *  Only CONFIRMED documents contribute (verified amounts). Totals sum the category
 *  buckets. Currency is assumed uniform per space for now (INR default); multi-
 *  currency grouping is a later refinement.
 * ============================================================================
 */
package com.trove.analytics;

import com.trove.space.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class AnalyticsService {

    private static final LocalDate MIN = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX = LocalDate.of(2999, 12, 31);

    private final AnalyticsRepository analyticsRepository;
    private final SpaceAuthorization authorization;

    public AnalyticsService(AnalyticsRepository analyticsRepository, SpaceAuthorization authorization) {
        this.analyticsRepository = analyticsRepository;
        this.authorization = authorization;
    }

    /** Spend grouped by category over [from, to] (nulls → wide-open range). */
    @Transactional(readOnly = true)
    public List<CategorySpendResponse> byCategory(UUID spaceId, UUID userId, LocalDate from, LocalDate to) {
        authorization.requireCanRead(spaceId, userId);
        return analyticsRepository.spendByCategory(spaceId, orMin(from), orMax(to)).stream()
                .map(c -> new CategorySpendResponse(c.getCategoryCode(), c.getCategoryLabel(),
                        c.getTotal(), c.getCount()))
                .toList();
    }

    /** Spend grouped by month (YYYY-MM). */
    @Transactional(readOnly = true)
    public List<MonthlySpendResponse> byMonth(UUID spaceId, UUID userId, LocalDate from, LocalDate to) {
        authorization.requireCanRead(spaceId, userId);
        return analyticsRepository.spendByMonth(spaceId, orMin(from), orMax(to)).stream()
                .map(m -> new MonthlySpendResponse(m.getPeriod(), m.getTotal(), m.getCount()))
                .toList();
    }

    /** Overall total + per-category breakdown for the range. */
    @Transactional(readOnly = true)
    public SpendSummary summary(UUID spaceId, UUID userId, LocalDate from, LocalDate to) {
        List<CategorySpendResponse> byCategory = byCategory(spaceId, userId, from, to);
        BigDecimal total = byCategory.stream()
                .map(CategorySpendResponse::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = byCategory.stream().mapToLong(CategorySpendResponse::count).sum();
        return new SpendSummary(orMin(from), orMax(to), total, count, byCategory);
    }

    private LocalDate orMin(LocalDate d) {
        return d != null ? d : MIN;
    }

    private LocalDate orMax(LocalDate d) {
        return d != null ? d : MAX;
    }

    // ── response records ───────────────────────────────────────────────────────

    public record CategorySpendResponse(String category, String label, BigDecimal total, long count) {
    }

    public record MonthlySpendResponse(String period, BigDecimal total, long count) {
    }

    public record SpendSummary(LocalDate from, LocalDate to, BigDecimal total, long count,
                               List<CategorySpendResponse> byCategory) {
    }
}
