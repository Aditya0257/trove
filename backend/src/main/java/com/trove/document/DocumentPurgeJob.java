/*
 * ============================================================================
 *  DocumentPurgeJob — hard-deletes trashed documents past their retention window
 * ============================================================================
 *  Purpose:        on a schedule, permanently purge documents that have sat in the
 *                  trash longer than the retention window (default 30 days).
 *  Business use:    completes the soft-delete story — an accidental delete is
 *                  recoverable for a month, then the file is cleared from live storage
 *                  and the DB (and, via the purge event, from Drive) for good.
 *  Design:         retention + cadence are configurable; opt-out via enabled=false.
 *                  Per-document failures are isolated inside DocumentService.purgeExpired.
 * ============================================================================
 */
package com.trove.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentPurgeJob.class);

    private final DocumentService documentService;
    private final boolean enabled;
    private final int retentionDays;

    public DocumentPurgeJob(DocumentService documentService,
                            @Value("${trove.trash.purge-enabled:true}") boolean enabled,
                            @Value("${trove.trash.retention-days:30}") int retentionDays) {
        this.documentService = documentService;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${trove.trash.purge-fixed-delay-ms:86400000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            documentService.purgeExpired(retentionDays);
        } catch (Exception e) {
            log.warn("Trash purge sweep failed — {}", e.getMessage());
        }
    }
}
