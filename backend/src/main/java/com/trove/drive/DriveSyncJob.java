/*
 * ============================================================================
 *  DriveSyncJob — periodically syncs every connected space to Drive
 * ============================================================================
 *  Purpose:        on a schedule, run DriveSyncService.sync for each space that has a
 *                  connected Drive.
 *  Business use:    keeps the human-navigable Drive backup current without anyone
 *                  triggering it (DESIGN §4.3 scheduled Drive sync).
 *  Design:         opt-out via trove.drive.scheduled-sync-enabled. Per-space failures
 *                  are isolated so one bad connection doesn't stop the others.
 * ============================================================================
 */
package com.trove.drive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DriveSyncJob {

    private static final Logger log = LoggerFactory.getLogger(DriveSyncJob.class);

    private final DriveSyncService driveSyncService;
    private final boolean enabled;

    public DriveSyncJob(DriveSyncService driveSyncService,
                        @Value("${trove.drive.scheduled-sync-enabled:true}") boolean enabled) {
        this.driveSyncService = driveSyncService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${trove.drive.sync-fixed-delay-ms:3600000}")
    public void syncAll() {
        if (!enabled) {
            return;
        }
        for (UUID spaceId : driveSyncService.connectedSpaceIds()) {
            try {
                driveSyncService.sync(spaceId);
            } catch (Exception e) {
                log.warn("Scheduled Drive sync failed for space {} - {}", spaceId, e.getMessage());
            }
        }
    }
}
