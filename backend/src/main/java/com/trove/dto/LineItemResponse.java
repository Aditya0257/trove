/*
 * ============================================================================
 *  LineItemResponse — API view of a line item
 * ============================================================================
 *  Purpose:        serialized line item returned inside a DocumentResponse.
 * ============================================================================
 */
package com.trove.dto;

import java.math.BigDecimal;

public record LineItemResponse(
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount
) {
}
