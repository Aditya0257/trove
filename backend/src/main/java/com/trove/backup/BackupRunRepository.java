/*
 * ============================================================================
 *  BackupRunRepository — data access for backup-run logs
 * ============================================================================
 *  Purpose:        persist run records and list them newest-first for monitoring.
 * ============================================================================
 */
package com.trove.backup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BackupRunRepository extends JpaRepository<BackupRun, UUID> {

    List<BackupRun> findTop50ByOrderByStartedAtDesc();
}
