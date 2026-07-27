package com.trove.service;

import com.trove.entity.BackupRun;
import java.util.List;

/** Service contract for BackupRunService. */
public interface BackupRunService {
    BackupRun start(String kind);
    void success(BackupRun run, String location, String detail);
    void fail(BackupRun run, String detail);
    List<BackupRun> recent();
}
