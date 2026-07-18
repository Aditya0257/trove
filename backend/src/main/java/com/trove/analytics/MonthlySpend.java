/*
 * ============================================================================
 *  MonthlySpend — projection: total spend per calendar month
 * ============================================================================
 *  Purpose:        a read-only projection for the "spend by month" aggregate.
 *  Business use:    the trend line — spend per YYYY-MM, the basis for later anomaly
 *                  detection ("this month vs the trailing average").
 *  Design:         period is 'YYYY-MM' from the document date.
 * ============================================================================
 */
package com.trove.analytics;

import java.math.BigDecimal;

public interface MonthlySpend {
    String getPeriod();
    BigDecimal getTotal();
    long getCount();
}
