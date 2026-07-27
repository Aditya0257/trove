/*
 * ============================================================================
 *  PgDumpJob — snapshots Postgres and uploads the dump to object storage
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Runs pg_dump (custom format), uploads the artifact under backups/pg_dump/ in
 *  object storage, and logs a backup_run. Available on-demand and on a schedule.
 *
 *  Business use case
 *  -----------------
 *  A database snapshot is one leg of the "lose the host, lose zero documents" net.
 *  Storing it in the SAME durable object store as the files means one place holds
 *  both the originals and a full DB restore point.
 *
 *  Solution architecture
 *  ---------------------
 *  Shells out to pg_dump using the app's datasource connection. The scheduled run is
 *  opt-in (trove.backup.scheduled-dump-enabled); the on-demand path is always usable.
 *  Mirror-to-second-cloud and Google Drive sync are later phases — this covers the
 *  Postgres snapshot leg (DESIGN §4.3).
 *
 *  Reasoning & logic
 *  -----------------
 *  Custom format (-Fc) is already compressed and restore-friendly (pg_restore). The
 *  dump is written to a temp file, uploaded, then deleted — nothing durable stays on
 *  the stateless host.
 * ============================================================================
 */
package com.trove.service.impl;
import com.trove.service.BackupRunService;
import com.trove.enums.BackupKind;
import com.trove.entity.BackupRun;
import com.trove.config.BackupProperties;

import com.trove.integration.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PgDumpJob {

    private static final Logger log = LoggerFactory.getLogger(PgDumpJob.class);
    private static final Pattern JDBC = Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?]+).*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final StorageService storageService;
    private final BackupRunService backupRunService;
    private final BackupProperties props;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    public PgDumpJob(StorageService storageService, BackupRunService backupRunService,
                     BackupProperties props,
                     @Value("${spring.datasource.url}") String jdbcUrl,
                     @Value("${spring.datasource.username}") String dbUser,
                     @Value("${spring.datasource.password}") String dbPassword) {
        this.storageService = storageService;
        this.backupRunService = backupRunService;
        this.props = props;
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    /** Opt-in nightly dump. */
    @Scheduled(cron = "${trove.backup.dump-cron:0 0 3 * * *}")
    public void scheduledDump() {
        if (props.isScheduledDumpEnabled()) {
            runDump();
        }
    }

    /** Runs pg_dump and uploads the artifact. Returns the storage key it wrote. */
    public String runDump() {
        BackupRun run = backupRunService.start(BackupKind.PG_DUMP);
        Path tmp = null;
        try {
            Matcher m = JDBC.matcher(jdbcUrl);
            if (!m.matches()) {
                throw new IllegalStateException("Unrecognized datasource url: " + jdbcUrl);
            }
            String host = m.group(1);
            String port = m.group(2) != null ? m.group(2) : "5432";
            String db = m.group(3);

            tmp = Files.createTempFile("trove-pgdump-", ".dump");
            List<String> cmd = new ArrayList<>(List.of(
                    props.getPgDumpPath(), "-h", host, "-p", port, "-U", dbUser,
                    "-d", db, "-Fc", "-f", tmp.toString()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("PGPASSWORD", dbPassword);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                throw new IllegalStateException("pg_dump exited " + code + ": " + output);
            }

            byte[] bytes = Files.readAllBytes(tmp);
            String key = props.getPrefix() + "pg_dump/trove-" + LocalDateTime.now().format(STAMP) + ".dump";
            storageService.put(key, bytes, "application/octet-stream");

            backupRunService.success(run, key, "bytes=" + bytes.length);
            log.info("pg_dump uploaded to {} ({} bytes)", key, bytes.length);
            return key;
        } catch (Exception e) {
            backupRunService.fail(run, e.getMessage());
            throw new IllegalStateException("pg_dump failed: " + e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {
                    // best effort
                }
            }
        }
    }
}
