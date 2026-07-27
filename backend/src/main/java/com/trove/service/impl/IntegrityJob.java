/*
 * ============================================================================
 *  IntegrityJob — continuously verifies the backup promise
 * ============================================================================
 *  Purpose:        on a schedule, run a vault-wide integrity check and record it as a
 *                  backup_run, so "zero data loss" is proven over time, not assumed.
 *  Business use:    a failed/degraded run is visible in the Backups history and can
 *                  drive an alert — the difference between claiming reliability and
 *                  operating it.
 *  Design:         logs SUCCESS when every live document has its primary object, else
 *                  FAILED (missing primary = real data-loss risk). Opt-out via config.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.service.IntegrityService;
import com.trove.dto.GlobalCheck;

import com.trove.enums.BackupKind;
import com.trove.entity.BackupRun;
import com.trove.service.BackupRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntegrityJob {

    private static final Logger log = LoggerFactory.getLogger(IntegrityJob.class);

    private final IntegrityService integrityService;
    private final BackupRunService backupRunService;
    private final boolean enabled;

    public IntegrityJob(IntegrityService integrityService, BackupRunService backupRunService,
                        @Value("${trove.integrity.check-enabled:true}") boolean enabled) {
        this.integrityService = integrityService;
        this.backupRunService = backupRunService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${trove.integrity.check-fixed-delay-ms:86400000}", initialDelay = 60000)
    public void run() {
        if (!enabled) {
            return;
        }
        BackupRun runRow = backupRunService.start(BackupKind.INTEGRITY);
        try {
            GlobalCheck c = integrityService.globalCheck();
            String detail = "live=" + c.liveDocuments() + " missingPrimary=" + c.missingPrimary()
                    + " missingSidecar=" + c.missingSidecar() + " orphans=" + c.orphanObjects();
            if (c.missingPrimary() > 0) {
                backupRunService.fail(runRow, detail);
                log.warn("Integrity check FOUND MISSING PRIMARY objects - {}", detail);
            } else {
                backupRunService.success(runRow, "vault", detail);
                log.info("Integrity check OK - {}", detail);
            }
        } catch (Exception e) {
            backupRunService.fail(runRow, e.getMessage());
            log.warn("Integrity check errored - {}", e.getMessage());
        }
    }
}
