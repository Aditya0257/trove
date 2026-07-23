/*
 * ============================================================================
 *  AnalyticsRepository — aggregate spend queries over the document index
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Native aggregate queries that sum confirmed document amounts per category and
 *  per month within a space and date range.
 *
 *  Business use case
 *  -----------------
 *  Spend tracking and category history. Only CONFIRMED documents count, because the
 *  brief is emphatic that extracted numbers are never trusted until a human confirms
 *  them — so spend is computed only from human-verified amounts.
 *
 *  Solution architecture
 *  ---------------------
 *  A separate read repository over the `document` table. Native SQL (not JPQL)
 *  because it joins document→category on a plain FK column and uses to_char() for
 *  month bucketing. Returns interface projections.
 *
 *  Reasoning & logic
 *  -----------------
 *  from/to are always supplied (the service substitutes wide defaults for nulls) to
 *  avoid null-typed bind parameters in native SQL. Rows with null amount/date are
 *  excluded so they don't skew totals.
 * ============================================================================
 */
package com.trove.analytics;

import com.trove.document.Document;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AnalyticsRepository extends Repository<Document, UUID> {

    // Grouped by currency as well, so the service can convert each bucket to the caller's
    // display currency and then re-aggregate (amounts in different currencies can't be
    // summed directly).
    @Query(value = """
            select c.code as categoryCode,
                   c.label as categoryLabel,
                   coalesce(d.currency, 'INR') as currency,
                   coalesce(sum(d.amount), 0) as total,
                   count(*) as count
            from document d
            join category c on c.id = d.category_id
            where d.space_id = :spaceId
              and d.status = 'confirmed'
              and d.amount is not null
              and d.doc_date >= :from
              and d.doc_date <= :to
            group by c.code, c.label, coalesce(d.currency, 'INR')
            """, nativeQuery = true)
    List<CategorySpend> spendByCategory(@Param("spaceId") UUID spaceId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    // `fmt` is a Postgres to_char pattern chosen by the service per granularity
    // (day: YYYY-MM-DD, week: IYYY-"W"IW, month: YYYY-MM) — all zero-padded so string
    // ordering is chronological.
    @Query(value = """
            select to_char(d.doc_date, :fmt) as period,
                   coalesce(d.currency, 'INR') as currency,
                   coalesce(sum(d.amount), 0) as total,
                   count(*) as count
            from document d
            where d.space_id = :spaceId
              and d.status = 'confirmed'
              and d.amount is not null
              and d.doc_date is not null
              and d.doc_date >= :from
              and d.doc_date <= :to
            group by 1, 2
            """, nativeQuery = true)
    List<MonthlySpend> spendByPeriod(@Param("spaceId") UUID spaceId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     @Param("fmt") String fmt);
}
