/*
 * ============================================================================
 *  CategorySpend — projection: total spend per category
 * ============================================================================
 *  Purpose:        a read-only projection for the "spend by category" aggregate.
 *  Business use:    powers "where does my money go" — grouped totals per category.
 *  Design:         Spring Data maps native-query column aliases to these getters.
 * ============================================================================
 */
package com.trove.analytics;

import java.math.BigDecimal;

public interface CategorySpend {
    String getCategoryCode();
    String getCategoryLabel();
    String getCurrency();
    BigDecimal getTotal();
    long getCount();
}
