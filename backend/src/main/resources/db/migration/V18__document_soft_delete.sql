-- =============================================================================
--  V18 — soft delete: a recoverable trash instead of an immediate wipe
-- =============================================================================
--  Purpose:        make "Delete" reversible for a retention window before anything is
--                  purged for good, honouring the core principle (deletion must never
--                  be silent, unrecoverable data loss).
--  Business use:    an accidental delete can be undone from a Trash view for 30 days;
--                  after that a scheduled job purges the file from every tier (R2, the
--                  mirror, Drive) and finally removes the index row.
--  Design:         status gains a 'deleted' value. The live file is MOVED (not erased)
--                  to a trash prefix, its new key kept in trash_key; storage_key is left
--                  pointing at the original path so Restore knows where to put it back.
--                  deleted_at drives the purge schedule; deleted_by records who did it.
-- =============================================================================

alter table document add column deleted_at timestamptz;
alter table document add column deleted_by uuid references app_user(id);
alter table document add column trash_key text;   -- R2 key while trashed (null unless deleted)

-- Speeds the purge sweep that scans for documents past their retention window.
create index idx_document_deleted_at on document (deleted_at) where deleted_at is not null;
