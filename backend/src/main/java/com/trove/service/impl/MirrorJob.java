/*
 * ============================================================================
 *  MirrorJob — periodically mirrors the vault to the second cloud
 * ============================================================================
 *  Purpose:        on a schedule, run MirrorService.mirror if a mirror is configured.
 *  Business use:    keeps the independent second-cloud copy current automatically.
 *  Design:         no-op when trove.mirror is not configured; per-run errors logged.
 * ============================================================================
 */
package com.trove.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MirrorJob {

    private static final Logger log = LoggerFactory.getLogger(MirrorJob.class);

    private final MirrorService mirrorService;

    public MirrorJob(MirrorService mirrorService) {
        this.mirrorService = mirrorService;
    }

    @Scheduled(fixedDelayString = "${trove.mirror.sync-fixed-delay-ms:3600000}")
    public void run() {
        if (!mirrorService.isEnabled()) {
            return;
        }
        try {
            mirrorService.mirror();
        } catch (Exception e) {
            log.warn("Scheduled mirror failed - {}", e.getMessage());
        }
    }
}
