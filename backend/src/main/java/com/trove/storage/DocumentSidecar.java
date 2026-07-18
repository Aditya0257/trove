/*
 * ============================================================================
 *  DocumentSidecar — the self-describing JSON written next to every file
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  The exact shape of the sidecar `.json` that lives beside each stored file. It
 *  mirrors the document's DB row so the object store is fully self-describing.
 *
 *  Business use case
 *  -----------------
 *  THIS is the core-principle payload: "losing the entire app + database + host
 *  must lose ZERO documents." If Postgres is wiped, we scan the bucket and rebuild
 *  every document row from these sidecars. The DB is a cache; the sidecar is truth.
 *
 *  Solution architecture
 *  ---------------------
 *  Serialized by S3StorageService via the Spring ObjectMapper (ISO dates, UUIDs as
 *  strings). Written on upload and re-written after extraction and after confirm,
 *  so it always reflects the latest known state of the document.
 *
 *  Design
 *  ------
 *  Field-for-field matches the sidecar JSON in DESIGN.md §6.1. `extra` carries
 *  type-specific fields (e.g. unitsConsumed for an electricity bill).
 *
 *  Reasoning & logic
 *  -----------------
 *  A record (immutable) — each write builds a fresh snapshot from the current row,
 *  which is simpler and safer than mutating a shared object.
 * ============================================================================
 */
package com.trove.storage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record DocumentSidecar(
        UUID documentId,
        UUID spaceId,
        UUID uploadedBy,
        String storageKey,
        String sidecarKey,
        String fileHash,          // "sha256:...."
        String mimeType,
        long sizeBytes,
        String originalFilename,
        String category,          // category code, e.g. "electricity"
        String merchant,          // canonical merchant name
        LocalDate docDate,
        BigDecimal amount,
        String currency,
        LocalDate dueDate,
        String status,            // needs_review | confirmed
        boolean vital,
        BigDecimal extractionConfidence,
        String rawText,
        Map<String, Object> extra,
        Instant createdAt
) {
}
