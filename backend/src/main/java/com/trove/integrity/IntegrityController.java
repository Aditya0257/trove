/*
 * ============================================================================
 *  IntegrityController — backup-health endpoints
 * ============================================================================
 *  Purpose:        the per-space integrity report and the recent backup-run history
 *                  that power the Backups dashboard.
 *  Business use:    lets anyone in a space see, and trust, that their documents are
 *                  safely copied across all tiers.
 *  Design:         /api/integrity (authenticated). Space defaults to the caller's
 *                  personal space; any member may read.
 * ============================================================================
 */
package com.trove.integrity;

import com.trove.backup.BackupRun;
import com.trove.backup.BackupRunService;
import com.trove.common.security.CurrentUser;
import com.trove.integrity.IntegrityDtos.IntegrityReport;
import com.trove.space.SpaceAuthorization;
import com.trove.space.SpaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

    private final IntegrityService integrityService;
    private final BackupRunService backupRunService;
    private final SpaceService spaceService;
    private final SpaceAuthorization authorization;
    private final CurrentUser currentUser;

    public IntegrityController(IntegrityService integrityService, BackupRunService backupRunService,
                               SpaceService spaceService, SpaceAuthorization authorization,
                               CurrentUser currentUser) {
        this.integrityService = integrityService;
        this.backupRunService = backupRunService;
        this.spaceService = spaceService;
        this.authorization = authorization;
        this.currentUser = currentUser;
    }

    /** Live integrity report for a space (verifies each document across the tiers). */
    @GetMapping("/report")
    public IntegrityReport report(@RequestParam(value = "spaceId", required = false) UUID spaceId) {
        UUID user = currentUser.requireUserId();
        UUID space = spaceId != null ? spaceId : spaceService.personalSpaceId(user);
        authorization.requireCanRead(space, user);
        return integrityService.report(space);
    }

    /** Recent backup/verification runs (mirror, Drive sync, pg_dump, integrity, …). */
    @GetMapping("/history")
    public List<RunView> history() {
        currentUser.requireUserId();
        return backupRunService.recent().stream()
                .map(r -> new RunView(r.getKind(), r.getStatus(), r.getLocation(), r.getDetail(),
                        r.getStartedAt(), r.getFinishedAt()))
                .toList();
    }

    public record RunView(String kind, String status, String location, String detail,
                          Instant startedAt, Instant finishedAt) {
    }
}
