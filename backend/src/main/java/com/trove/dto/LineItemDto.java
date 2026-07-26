/*
 * ============================================================================
 *  LineItemDto — one extracted line item (value object)
 * ============================================================================
 *  Purpose:        a single row of an itemized document (e.g. a receipt line).
 *  Business use:    powers itemized views and, later, per-item search/analytics.
 *  Design:         matches DESIGN.md §6.2 record LineItemDto exactly
 *                  (description, quantity, amount). unit_price on the DB table is
 *                  left null unless a provider supplies it.
 * ============================================================================
 */
package com.trove.dto;

import java.math.BigDecimal;

public record LineItemDto(
        String description,
        BigDecimal quantity,
        BigDecimal amount
) {
}
