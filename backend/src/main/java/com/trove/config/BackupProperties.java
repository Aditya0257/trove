/*
 * ============================================================================
 *  BackupProperties — pg_dump job configuration
 * ============================================================================
 *  Purpose:        binds trove.backup.* (pg_dump binary path, scheduled toggle +
 *                  cron, and the storage prefix backups land under).
 *  Business use:    nightly database snapshots are one leg of the safety net; they
 *                  are uploaded to object storage alongside the files.
 *  Design:         scheduled dump is OFF by default (opt-in) so dev restarts don't
 *                  dump every hour; the on-demand endpoint always works.
 * ============================================================================
 */
package com.trove.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trove.backup")
public class BackupProperties {

    /** Path to the pg_dump binary (rely on PATH by default). */
    private String pgDumpPath = "pg_dump";

    /** Whether the scheduled nightly dump runs. */
    private boolean scheduledDumpEnabled = false;

    /** Cron for the scheduled dump (default 03:00 daily). */
    private String dumpCron = "0 0 3 * * *";

    /** Object-storage prefix backups are written under. */
    private String prefix = "backups/";

    public String getPgDumpPath() { return pgDumpPath; }
    public void setPgDumpPath(String pgDumpPath) { this.pgDumpPath = pgDumpPath; }

    public boolean isScheduledDumpEnabled() { return scheduledDumpEnabled; }
    public void setScheduledDumpEnabled(boolean scheduledDumpEnabled) { this.scheduledDumpEnabled = scheduledDumpEnabled; }

    public String getDumpCron() { return dumpCron; }
    public void setDumpCron(String dumpCron) { this.dumpCron = dumpCron; }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
}
