/*
 * ============================================================================
 *  ConfirmRequest — the reviewer's edits + acceptance
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  The body of POST /documents/{id}/confirm. Every field is optional: the reviewer
 *  may accept the extracted values as-is, or correct any of them before confirming.
 *
 *  Business use case
 *  -----------------
 *  This is the human-in-the-loop step the brief demands — "never trust extracted
 *  numbers silently." Confirming moves the document from needs_review to confirmed.
 *
 *  Design
 *  ------
 *  category is a CODE and merchant is a NAME (resolved server-side to ids). Nulls
 *  mean "leave as extracted." isVital lets the reviewer flag sensitive PII.
 * ============================================================================
 */
package com.trove.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ConfirmRequest(
        String category,          // category code override
        String merchant,          // merchant name override
        LocalDate docDate,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        Boolean vital,
        Map<String, Object> extra
) {
}
