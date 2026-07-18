/*
 * ============================================================================
 *  RebuildService — disaster recovery: rebuild the DB index from sidecars
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Scans every sidecar JSON in object storage and re-creates any missing document
 *  rows from them. This is the "the DB is a cache; the bucket is the truth" path.
 *
 *  Business use case
 *  -----------------
 *  The core promise: "losing the entire app + database + host must lose ZERO
 *  documents." If Postgres is wiped, this rebuilds the index straight from the
 *  self-describing bucket — no app or DB backup required (CLAUDE.md, DESIGN §4.5).
 *
 *  Solution architecture
 *  ---------------------
 *  Lists *.json objects, parses each into a DocumentSidecar, and for any whose
 *  documentId is not already present, restores the row with its ORIGINAL id and
 *  fields (Document.restore). Category/merchant are re-resolved from their
 *  code/name. Logs a backup_run.
 *
 *  Reasoning & logic
 *  -----------------
 *  Per-record via repository-level transactions so one bad sidecar can't abort the
 *  whole rebuild (failures are counted, not fatal). Idempotent: existing ids are
 *  skipped, so it can be run repeatedly. Line items are not restored (not in the
 *  sidecar) — a known, documented limitation.
 * ============================================================================
 */
package com.trove.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trove.category.Category;
import com.trove.category.CategoryService;
import com.trove.document.Document;
import com.trove.document.DocumentRepository;
import com.trove.merchant.Merchant;
import com.trove.merchant.MerchantService;
import com.trove.storage.DocumentSidecar;
import com.trove.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RebuildService {

    private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final CategoryService categoryService;
    private final MerchantService merchantService;
    private final BackupRunService backupRunService;
    private final ObjectMapper objectMapper;

    public RebuildService(StorageService storageService, DocumentRepository documentRepository,
                          CategoryService categoryService, MerchantService merchantService,
                          BackupRunService backupRunService, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.categoryService = categoryService;
        this.merchantService = merchantService;
        this.backupRunService = backupRunService;
        this.objectMapper = objectMapper;
    }

    /** Rebuilds missing document rows from every sidecar in the bucket. */
    public RebuildSummary rebuild() {
        BackupRun run = backupRunService.start(BackupKind.REBUILD);
        int scanned = 0;
        int rebuilt = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<String> keys = storageService.list("");
            for (String key : keys) {
                if (!key.endsWith(".json")) {
                    continue;
                }
                scanned++;
                try {
                    if (restoreOne(key)) {
                        rebuilt++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Rebuild: failed for sidecar {} ({})", key, e.getMessage());
                }
            }
            String detail = "scanned=" + scanned + " rebuilt=" + rebuilt
                    + " skipped=" + skipped + " failed=" + failed;
            backupRunService.success(run, "bucket", detail);
            log.info("DR rebuild complete — {}", detail);
            return new RebuildSummary(scanned, rebuilt, skipped, failed);
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Rebuild failed: " + e.getMessage(), e);
        }
    }

    /** Restores one sidecar; returns true if a row was created, false if it existed. */
    private boolean restoreOne(String sidecarKey) throws Exception {
        byte[] bytes = storageService.get(sidecarKey);
        DocumentSidecar s = objectMapper.readValue(bytes, DocumentSidecar.class);
        if (s.documentId() == null) {
            return false;
        }
        if (documentRepository.existsById(s.documentId())) {
            return false;
        }

        Category category = categoryService.resolve(s.spaceId(), s.category());
        Merchant merchant = merchantService.resolve(s.merchant());
        String hex = s.fileHash() != null && s.fileHash().startsWith("sha256:")
                ? s.fileHash().substring("sha256:".length()) : s.fileHash();
        String mime = s.mimeType() != null ? s.mimeType() : "application/octet-stream";

        Document doc = Document.restore(
                s.documentId(), s.spaceId(), s.uploadedBy(), s.storageKey(),
                s.sidecarKey() != null ? s.sidecarKey() : sidecarKey, hex, mime, s.sizeBytes(),
                s.originalFilename(), category.getId(),
                merchant != null ? merchant.getId() : null,
                s.docDate(), s.amount(), s.currency(), s.dueDate(), s.rawText(), s.extra(),
                s.extractionConfidence(), s.vital(), s.encrypted(), s.status());
        documentRepository.save(doc);
        log.info("Rebuilt document {} from sidecar {}", s.documentId(), sidecarKey);
        return true;
    }

    /** Summary of a rebuild pass. */
    public record RebuildSummary(int scanned, int rebuilt, int skipped, int failed) {
    }
}
