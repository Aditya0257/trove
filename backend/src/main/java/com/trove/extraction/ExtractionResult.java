/*
 * ============================================================================
 *  ExtractionResult — structured output of reading a document
 * ============================================================================
 *  Purpose:        the "one call in, structured JSON out" result of an
 *                  ExtractionProvider: category, merchant, dates, amount, items,
 *                  raw text, type-specific extras, and a confidence score.
 *  Business use:    these are the fields a human then reviews/confirms; the numbers
 *                  are never trusted silently (brief) — confidence signals doubt.
 *  Design:         matches DESIGN.md §6.2 record ExtractionResult exactly.
 * ============================================================================
 */
package com.trove.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ExtractionResult(
        String categoryCode,        // 'electricity', 'shopping', 'insurance', ...
        String merchantName,        // raw, before normalization
        LocalDate docDate,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        List<LineItemDto> lineItems,// may be empty
        String rawText,
        Map<String, Object> extra,  // type-specific fields
        BigDecimal confidence       // 0..1
) {
}
