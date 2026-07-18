/*
 * ============================================================================
 *  DocumentResponse — API view of a document
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  The JSON a client receives for a document: identity, file facts, extracted/
 *  confirmed fields (with category code + merchant name resolved for display), a
 *  short-lived file URL, review state, and line items.
 *
 *  Business use case
 *  -----------------
 *  Powers the review-and-confirm screen and the "list by category" view. Exposes
 *  category CODE and merchant NAME (not raw UUIDs) so clients are human-friendly.
 *
 *  Design
 *  ------
 *  fileUrl is a presigned, expiring URL so clients can render the original without
 *  the backend proxying bytes. extractionConfidence surfaces model doubt to the
 *  reviewer — the brief insists numbers are never trusted silently.
 * ============================================================================
 */
package com.trove.document.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID spaceId,
        UUID uploadedBy,
        String storageKey,
        String sidecarKey,
        String fileHash,
        String mimeType,
        long sizeBytes,
        String originalFilename,
        String category,          // category code
        String merchant,          // canonical merchant name
        LocalDate docDate,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        String rawText,
        Map<String, Object> extra,
        BigDecimal extractionConfidence,
        boolean vital,
        String status,
        UUID reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        String fileUrl,           // short-lived presigned URL
        List<LineItemResponse> lineItems
) {
}
