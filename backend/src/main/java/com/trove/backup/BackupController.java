/*
 * ============================================================================
 *  BackupController — export/import + admin recovery operations
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Endpoints for the on-demand export ZIP (per space), and admin-only restore
 *  operations: import a ZIP, rebuild the index from sidecars, trigger a pg_dump, and
 *  view backup-run history.
 *
 *  Business use case
 *  -----------------
 *  Gives the user a one-click "download everything" and gives an operator the
 *  recovery levers behind "lose the host, lose zero documents."
 *
 *  Solution architecture
 *  ---------------------
 *  Export is space-scoped (any member). Import/rebuild/pg-dump/runs are system-wide,
 *  so they are gated to the admin (seeded dev) user until a full role model exists.
 *  All operations are logged to backup_run.
 * ============================================================================
 */
package com.trove.backup;

import com.trove.common.DevProperties;
import com.trove.common.error.ForbiddenException;
import com.trove.common.security.CurrentUser;
import com.trove.space.SpaceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class BackupController {

    private final ExportService exportService;
    private final ImportService importService;
    private final RebuildService rebuildService;
    private final PgDumpJob pgDumpJob;
    private final MirrorService mirrorService;
    private final BackupRunService backupRunService;
    private final SpaceService spaceService;
    private final CurrentUser currentUser;
    private final DevProperties dev;

    public BackupController(ExportService exportService, ImportService importService,
                           RebuildService rebuildService, PgDumpJob pgDumpJob,
                           MirrorService mirrorService,
                           BackupRunService backupRunService, SpaceService spaceService,
                           CurrentUser currentUser, DevProperties dev) {
        this.exportService = exportService;
        this.importService = importService;
        this.rebuildService = rebuildService;
        this.pgDumpJob = pgDumpJob;
        this.mirrorService = mirrorService;
        this.backupRunService = backupRunService;
        this.spaceService = spaceService;
        this.currentUser = currentUser;
        this.dev = dev;
    }

    /** Download a full export ZIP for a space (defaults to the caller's personal space). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        byte[] zip = exportService.exportSpace(space, user);
        String filename = "vault-export-" + LocalDate.now() + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    /** Restore from an export ZIP (admin only). */
    @PostMapping("/import")
    public RebuildService.RebuildSummary importZip(@RequestPart("file") MultipartFile file) throws IOException {
        requireAdmin();
        return importService.importZip(file.getBytes());
    }

    /** Rebuild the document index from object-storage sidecars (admin only). */
    @PostMapping("/admin/rebuild")
    public RebuildService.RebuildSummary rebuild() {
        requireAdmin();
        return rebuildService.rebuild();
    }

    /** Trigger a pg_dump to object storage now (admin only). */
    @PostMapping("/admin/pg-dump")
    public Map<String, String> pgDump() {
        requireAdmin();
        return Map.of("key", pgDumpJob.runDump());
    }

    /** Mirror the vault to the configured second cloud now (admin only). */
    @PostMapping("/admin/mirror")
    public MirrorService.MirrorSummary mirror() {
        requireAdmin();
        return mirrorService.mirror();
    }

    /** Recent backup-run history (admin only). */
    @GetMapping("/admin/backup-runs")
    public List<RunView> runs() {
        requireAdmin();
        return backupRunService.recent().stream()
                .map(r -> new RunView(r.getId(), r.getKind(), r.getStatus(), r.getLocation(),
                        r.getStartedAt(), r.getFinishedAt(), r.getDetail()))
                .toList();
    }

    private void requireAdmin() {
        if (!currentUser.requireUserId().equals(dev.getDefaultUserId())) {
            throw new ForbiddenException("Admin only");
        }
    }

    public record RunView(UUID id, String kind, String status, String location,
                          Instant startedAt, Instant finishedAt, String detail) {
    }
}
