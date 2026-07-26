/*
 * ============================================================================
 *  DriveTrashListener — mirrors soft-delete lifecycle into each connected Drive
 * ============================================================================
 *
 *  Purpose
 *  -------
 *  Keeps the human-navigable Drive backup in step with the trash: on soft-delete the
 *  file moves into Trove/_Deleted/, on restore it moves back to its category/month
 *  folder, and on purge it is deleted from the Drive.
 *
 *  Business use case
 *  -----------------
 *  "Open Drive and find the document" must stay true — a deleted document shouldn't sit
 *  in its normal folder as if live, and a purged one must be gone from Drive too, so the
 *  Drive copy matches what the app shows.
 *
 *  Solution architecture
 *  ---------------------
 *  Listens for the document feature's lifecycle events, avoiding a document→drive
 *  dependency. Trash/restore run AFTER_COMMIT (the sync rows still exist, and slow Drive
 *  I/O stays off the request transaction). Purge runs SYNCHRONOUSLY on the event, before
 *  the row + drive_sync rows are cascade-deleted, so the file ids are still readable.
 *
 *  Reasoning & logic
 *  -----------------
 *  Every Drive call is best-effort inside DriveSyncService — the trash state in the DB is
 *  the source of truth; a Drive hiccup must never break delete/restore/purge in the app.
 * ============================================================================
 */
package com.trove.service.impl;

import com.trove.event.DocumentPurgedEvent;
import com.trove.event.DocumentRestoredEvent;
import com.trove.event.DocumentTrashedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class DriveTrashListener {

    private final DriveSyncService driveSyncService;

    public DriveTrashListener(DriveSyncService driveSyncService) {
        this.driveSyncService = driveSyncService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTrashed(DocumentTrashedEvent event) {
        driveSyncService.moveToDeletedFolder(event.documentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRestored(DocumentRestoredEvent event) {
        driveSyncService.moveOutOfDeletedFolder(event.documentId());
    }

    /** Synchronous (not AFTER_COMMIT): runs before the cascade removes the drive_sync rows. */
    @EventListener
    public void onPurged(DocumentPurgedEvent event) {
        driveSyncService.deleteFromDrives(event.documentId());
    }
}
