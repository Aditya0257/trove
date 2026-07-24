# LLD: Drive, Mirror, Backup and Integrity

Modules: `drive`, `backup`, `integrity`. This is the implementation of the resilience model;
the model itself is in
[../architecture/04-resilience-and-backup.md](../architecture/04-resilience-and-backup.md).

## 1. Google Drive (Tier 3)

| Class | Role |
| --- | --- |
| `GoogleDriveController` | authorize-url, connect, callback, status, sync, mode, activate, disconnect. |
| `GoogleDriveOAuthService` | The OAuth exchange; stores the encrypted refresh token; reads account identity and quota via `about.get`. |
| `DriveSyncService` / `DriveSyncJob` | Copy confirmed documents into the folder tree; the job runs hourly. |
| `DriveConnection` / `DriveFolder` / `DriveRepositories` | The linked Drives, the per-connection folder-id cache, and their repositories. |
| `DocumentSync` / `DocumentSyncId` | Idempotency records: a document's copy at a target with its external id. |
| `DriveTrashListener` | Moves the Drive copy to and from `_Deleted` on document trash, restore and purge events. |
| `GoogleOAuthProperties` | Client id and secret, scopes, redirect. |

Design points (D17): per-space-owner OAuth with the `drive.file` scope (least privilege, not a
service account); pooling of several Drives per space with `rotate` or `mirror` mode
(`space.drive_sync_mode`); per-connection folder tree and sync state (each Drive has its own
tree); quota tracking per connection; idempotent sync keyed by `document_sync`. The tree is
`Trove / {space} / {category} / {YYYY-MM} / file`.

## 2. Backblaze B2 mirror (Tier 2)

| Class | Role |
| --- | --- |
| `MirrorService` / `MirrorJob` | Key-diff copy from R2 to B2; hourly. |
| `MirrorProperties` | The B2 endpoint, keys and bucket. |

The mirror lists R2 keys and B2 keys and copies only the difference, so it is idempotent and
cheap. It is append-only: it accumulates and never deletes, so a purged document still has an
archival copy in B2 (D19). This is the guarantee that a delete, accidental or malicious, cannot
erase everything.

## 3. Backup, export, import, disaster recovery

| Class | Role |
| --- | --- |
| `BackupController` | export, import, and the admin jobs (rebuild, pg-dump, mirror, backup-runs). |
| `ExportService` | Streams the full ZIP: `manifest.json` (complete records), `data.csv` (flattened), `files/` (originals plus sidecars, mirroring the object-store layout). |
| `ImportService` | Restores from an uploaded export ZIP, including onto a fresh system. |
| `PgDumpJob` | Nightly database dump (a fast restore path; not the source of truth). |
| `RebuildService` | Rebuilds the index by scanning the bucket and reading sidecars. |
| `BackupRun` / `BackupRunService` / `BackupRunRepository` | Log every backup and DR job outcome to `backup_run` for the admin view. |
| `BackupKind` / `BackupProperties` | Job kinds and scheduling. |

Recovery order of preference: restore the latest pg_dump (fastest), rebuild from sidecars
(authoritative), or import an export ZIP (works even onto a brand-new system).

## 4. Integrity verification

| Class | Role |
| --- | --- |
| `IntegrityService` | Verify the three tiers agree; find gaps and orphans; rank by danger. |
| `IntegrityJob` | Daily run. |
| `IntegrityController` | `GET /api/integrity/report` and `/history`. |
| `IntegrityDtos` | The report shapes. |

It lists R2 (and B2 if configured) into a set, and for each document checks the live object and
sidecar in R2, a mirror copy in B2, and a Drive copy (from the sync records). A missing live
object or sidecar is the most serious finding; a missing mirror or Drive copy is a redundancy
warning (and "not in Drive" only counts once the space has a Drive linked). The reverse check
finds orphans (objects with no row), which are expected and demonstrate the database is a
rebuildable projection. Trash under `_trash/` is accounted for separately.

## 5. Data and configuration

- Data: `drive_connection`, `drive_folder`, `document_sync`, `backup_run`. See the
  [data model](../architecture/02-data-model.md).
- Configuration: `trove.mirror.*` (B2), the Google OAuth properties, and the backup schedule.
  See [../operations/configuration.md](../operations/configuration.md).
