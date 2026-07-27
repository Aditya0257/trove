/*
 * ============================================================================
 *  BackupRunService — records the lifecycle of each safety operation
 * ============================================================================
 *  Purpose:        start/success/fail helpers that write backup_run rows in their
 *                  own transactions, so a run is logged even if the operation fails.
 *  Business use:    an auditable trail proving backups/exports/rebuilds ran.
 *  Design:         REQUIRES_NEW so the log commits independently of the operation's
 *                  own transaction (a failed op still leaves a 'failed' record).
 * ============================================================================
 */
package com.trove.service.impl;

import com.trove.service.BackupRunService;
import com.trove.enums.BackupKind;
import com.trove.entity.BackupRun;
import com.trove.repository.BackupRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class BackupRunServiceImpl implements BackupRunService {

    private final BackupRunRepository repository;

    public BackupRunServiceImpl(BackupRunRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BackupRun start(String kind) {
        return repository.save(new BackupRun(kind, BackupKind.Status.RUNNING));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(BackupRun run, String location, String detail) {
        run.setStatus(BackupKind.Status.SUCCESS);
        run.setLocation(location);
        run.setDetail(detail);
        run.setFinishedAt(Instant.now());
        repository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(BackupRun run, String detail) {
        run.setStatus(BackupKind.Status.FAILED);
        run.setDetail(detail);
        run.setFinishedAt(Instant.now());
        repository.save(run);
    }

    @Transactional(readOnly = true)
    public List<BackupRun> recent() {
        return repository.findTop50ByOrderByStartedAtDesc();
    }
}
