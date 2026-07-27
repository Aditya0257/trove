package com.trove.service;

import com.trove.dto.CategorySpendResponse;
import com.trove.dto.MonthlySpendResponse;
import com.trove.dto.SpendSummary;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Service contract for AnalyticsService. */
public interface AnalyticsService {
    List<CategorySpendResponse> byCategory(UUID spaceId, UUID userId, LocalDate from, LocalDate to, String displayCcy);
    List<MonthlySpendResponse> byMonth(UUID spaceId, UUID userId, LocalDate from, LocalDate to, String displayCcy, String granularity);
    SpendSummary summary(UUID spaceId, UUID userId, LocalDate from, LocalDate to, String displayCcy);
}
