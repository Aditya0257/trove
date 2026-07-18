/*
 * ============================================================================
 *  BackupKind / BackupStatus — constants for backup_run rows
 * ============================================================================
 *  Purpose:        the kinds of safety operations and their statuses.
 *  Business use:    pg_dump/export/import/rebuild are the provider-independent
 *                  safety net; mirror/drive_sync are the scheduled cloud fan-out
 *                  (later). Statuses track running/success/failed for observability.
 * ============================================================================
 */
package com.trove.backup;

public final class BackupKind {

    public static final String PG_DUMP = "pg_dump";
    public static final String DRIVE_SYNC = "drive_sync";
    public static final String MIRROR = "mirror";
    public static final String EXPORT = "export";
    public static final String IMPORT = "import";
    public static final String REBUILD = "rebuild";

    private BackupKind() {
    }

    public static final class Status {
        public static final String RUNNING = "running";
        public static final String SUCCESS = "success";
        public static final String FAILED = "failed";

        private Status() {
        }
    }
}
