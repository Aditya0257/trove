/*
 * ============================================================================
 *  ImportService — restore the system from an export ZIP
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Reads an export ZIP, writes its files/ (originals + sidecars) back into object
 *  storage at their exact keys, then rebuilds the DB index from those sidecars.
 *
 *  Business use case
 *  -----------------
 *  The "upload that ZIP back and it fully restores" guarantee (CLAUDE.md) — the
 *  provider-independent safety net: even with a brand-new bucket and empty DB, an
 *  export archive reconstitutes the vault.
 *
 *  Solution architecture
 *  ---------------------
 *  Restoring files first, then reusing RebuildService means import and disaster
 *  recovery share the same faithful, idempotent row-restore path (from sidecars).
 *  Logs a backup_run.
 *
 *  Reasoning & logic
 *  -----------------
 *  Content type is inferred (.json → application/json, else octet-stream). Because
 *  the row rebuild is delegated, import is idempotent and preserves original ids.
 * ============================================================================
 */
package com.trove.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.trove.storage.StorageService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final String FILES_PREFIX = "files/";

    private final StorageService storageService;
    private final RebuildService rebuildService;
    private final BackupRunService backupRunService;

    public ImportService(StorageService storageService, RebuildService rebuildService,
                         BackupRunService backupRunService) {
        this.storageService = storageService;
        this.rebuildService = rebuildService;
        this.backupRunService = backupRunService;
    }

    /** Restores files from the ZIP into storage, then rebuilds rows from sidecars. */
    public RebuildService.RebuildSummary importZip(byte[] zipBytes) {
        BackupRun run = backupRunService.start(BackupKind.IMPORT);
        int restoredObjects = 0;
        try {
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().startsWith(FILES_PREFIX)) {
                        continue;
                    }
                    String key = entry.getName().substring(FILES_PREFIX.length());
                    if (key.isBlank()) {
                        continue;
                    }
                    byte[] bytes = readAll(zin);
                    String contentType = key.endsWith(".json") ? "application/json" : "application/octet-stream";
                    storageService.put(key, bytes, contentType);
                    restoredObjects++;
                }
            }
            // Now that the bucket holds the files + sidecars, rebuild the index.
            RebuildService.RebuildSummary summary = rebuildService.rebuild();
            backupRunService.success(run, "bucket",
                    "restoredObjects=" + restoredObjects + " " + summary);
            log.info("Import complete — {} objects restored, {}", restoredObjects, summary);
            return summary;
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("Import failed: " + e.getMessage(), e);
        }
    }

    private byte[] readAll(ZipInputStream zin) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zin.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
