/*
 * ============================================================================
 *  AnomalyRepository — trailing amounts for the same category
 * ============================================================================
 *  Purpose:        fetch prior CONFIRMED amounts in a space + category within a
 *                  lookback window, to build the baseline average.
 *  Business use:    the baseline for "higher than usual" is the recent history of
 *                  the same category (e.g. past electricity bills).
 *  Design:         JPQL over Document (category is a plain UUID column, no join
 *                  needed). Excludes the document under evaluation.
 * ============================================================================
 */
package com.trove.anomaly;

import com.trove.document.Document;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AnomalyRepository extends Repository<Document, UUID> {

    @Query("""
           select d.amount from Document d
           where d.spaceId = :spaceId
             and d.categoryId = :categoryId
             and d.status = 'confirmed'
             and d.amount is not null
             and d.id <> :excludeId
             and d.docDate is not null
             and d.docDate >= :from
             and d.docDate <= :to
           """)
    List<BigDecimal> priorConfirmedAmounts(@Param("spaceId") UUID spaceId,
                                           @Param("categoryId") UUID categoryId,
                                           @Param("excludeId") UUID excludeId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);
}
