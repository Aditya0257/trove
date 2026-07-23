/*
 * ============================================================================
 *  IntegrityDtos — the backup-integrity report shapes
 * ============================================================================
 *  Purpose:        the health report a client renders: per-tier coverage for a space's
 *                  documents, the specific documents with gaps, and global storage stats.
 *  Business use:    operationalises the core principle — instead of CLAIMING "three
 *                  copies, zero data loss", we continuously VERIFY it and surface drift.
 *  Design:         mirrorEnabled=false means the B2 tier isn't configured (its coverage
 *                  is reported as not-applicable). Severity ranks issues so a missing
 *                  primary (real data-loss risk) outranks a missing mirror/Drive copy.
 * ============================================================================
 */
package com.trove.integrity;

import java.time.Instant;
import java.util.List;

public final class IntegrityDtos {

    /** One document with a coverage gap. */
    public record Issue(String documentId, String title, String severity, String problem) {
    }

    /** Global object-store view (keys aren't space-scoped, so this spans the vault). */
    public record StorageIntegrity(long r2Objects, long indexedKeys, long orphanObjects,
                                   long rebuildableOrphans, boolean mirrorEnabled, long mirrorObjects) {
    }

    /** Per-tier coverage counts + the failing documents + global storage stats. */
    public record IntegrityReport(
            String spaceId,
            Instant checkedAt,
            int documents,
            int primaryOk,       // R2 file present
            int sidecarOk,       // R2 sidecar JSON present
            Integer mirrorOk,    // present in B2 (null when mirror disabled)
            int driveOk,         // synced to at least one Drive
            int criticalCount,   // issues that risk data loss (missing primary)
            List<Issue> issues,
            StorageIntegrity storage) {
    }

    private IntegrityDtos() {
    }
}
