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
package com.trove.service.impl;
import com.trove.dto.CategorySpend;
import com.trove.dto.MonthlySpend;
import com.trove.repository.AnalyticsRepository;

import com.trove.security.SpaceAuthorization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalyticsService {

    private static final LocalDate MIN = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX = LocalDate.of(2999, 12, 31);

    private final AnalyticsRepository analyticsRepository;
    private final SpaceAuthorization authorization;
    private final ExchangeRateService fx;

    public AnalyticsService(AnalyticsRepository analyticsRepository, SpaceAuthorization authorization,
                            ExchangeRateService fx) {
        this.analyticsRepository = analyticsRepository;
        this.authorization = authorization;
        this.fx = fx;
    }

    /** Spend by category over [from, to], every amount converted to {@code displayCcy}. */
    @Transactional(readOnly = true)
    public List<CategorySpendResponse> byCategory(UUID spaceId, UUID userId, LocalDate from, LocalDate to,
                                                  String displayCcy) {
        authorization.requireCanRead(spaceId, userId);
        String ccy = normalize(displayCcy);
        // Fold the per-(category, currency) rows into per-category totals in one currency.
        Map<String, Agg> byCat = new LinkedHashMap<>();
        for (CategorySpend c : analyticsRepository.spendByCategory(spaceId, orMin(from), orMax(to))) {
            Agg a = byCat.computeIfAbsent(c.getCategoryCode(), k -> new Agg(c.getCategoryLabel()));
            a.total = a.total.add(fx.convert(c.getTotal(), c.getCurrency(), ccy));
            a.count += c.getCount();
        }
        return byCat.entrySet().stream()
                .map(e -> new CategorySpendResponse(e.getKey(), e.getValue().label, e.getValue().total, e.getValue().count))
                .sorted((x, y) -> y.total().compareTo(x.total()))
                .toList();
    }

    /** Spend over time at a granularity (day/week/month), converted to {@code displayCcy}. */
    @Transactional(readOnly = true)
    public List<MonthlySpendResponse> byMonth(UUID spaceId, UUID userId, LocalDate from, LocalDate to,
                                              String displayCcy, String granularity) {
        authorization.requireCanRead(spaceId, userId);
        String ccy = normalize(displayCcy);
        String fmt = switch (granularity == null ? "" : granularity.toLowerCase()) {
            case "day" -> "YYYY-MM-DD";
            case "week" -> "IYYY-\"W\"IW";
            default -> "YYYY-MM";
        };
        Map<String, Agg> byPeriod = new LinkedHashMap<>();
        for (MonthlySpend m : analyticsRepository.spendByPeriod(spaceId, orMin(from), orMax(to), fmt)) {
            Agg a = byPeriod.computeIfAbsent(m.getPeriod(), k -> new Agg(k));
            a.total = a.total.add(fx.convert(m.getTotal(), m.getCurrency(), ccy));
            a.count += m.getCount();
        }
        return byPeriod.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new MonthlySpendResponse(e.getKey(), e.getValue().total, e.getValue().count))
                .toList();
    }

    /** Overall total + per-category breakdown, all in {@code displayCcy}. */
    @Transactional(readOnly = true)
    public SpendSummary summary(UUID spaceId, UUID userId, LocalDate from, LocalDate to, String displayCcy) {
        String ccy = normalize(displayCcy);
        List<CategorySpendResponse> byCategory = byCategory(spaceId, userId, from, to, ccy);
        BigDecimal total = byCategory.stream()
                .map(CategorySpendResponse::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = byCategory.stream().mapToLong(CategorySpendResponse::count).sum();
        return new SpendSummary(orMin(from), orMax(to), ccy, fx.asOf(), total, count, byCategory);
    }

    private String normalize(String c) {
        if (c == null || c.isBlank()) {
            return ExchangeRateService.DEFAULT;
        }
        String up = c.trim().toUpperCase();
        return ExchangeRateService.SUPPORTED.contains(up) ? up : ExchangeRateService.DEFAULT;
    }

    private LocalDate orMin(LocalDate d) {
        return d != null ? d : MIN;
    }

    private LocalDate orMax(LocalDate d) {
        return d != null ? d : MAX;
    }

    /** Mutable per-group accumulator used while folding currency buckets together. */
    private static final class Agg {
        final String label;
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        Agg(String label) {
            this.label = label;
        }
    }

    // ── response records ───────────────────────────────────────────────────────

    public record CategorySpendResponse(String category, String label, BigDecimal total, long count) {
    }

    public record MonthlySpendResponse(String period, BigDecimal total, long count) {
    }

    public record SpendSummary(LocalDate from, LocalDate to, String currency, Instant ratesAsOf,
                               BigDecimal total, long count, List<CategorySpendResponse> byCategory) {
    }
}
