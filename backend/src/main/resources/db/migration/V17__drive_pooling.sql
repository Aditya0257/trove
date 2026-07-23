-- =============================================================================
--  V17 — Drive pooling: many Drives per space, per-connection keys, sync mode
-- =============================================================================
--  Purpose:        let a space back up into SEVERAL Google Drives at once — either
--                  spreading documents to aggregate free space (rotate) or copying
--                  every document into all of them for redundancy (mirror).
--  Business use:    members pool their own 15 GB Drives behind one space; "mirror"
--                  gives a second, independent Tier-3 copy of every file.
--  Design:         each Drive has its OWN "Trove" folder tree and its OWN copy of a
--                  document, so the folder-id cache and per-document sync state move
--                  from being keyed by space to being keyed by connection. The active
--                  write target (rotate mode) is flagged on the connection; the mode
--                  itself is a per-space setting.
--
--  Data reshape:   drive_folder.connection_id and document_sync.connection_id are
--                  backfilled from each space's single existing connection (safe today
--                  because the old schema allowed only one Drive per space). Rows that
--                  can't be attributed (space lost its Drive) are dropped — harmless,
--                  they simply re-sync on the next run.
-- =============================================================================

-- 1. How a space spreads its backup across its Drives.
alter table space add column drive_sync_mode text not null default 'rotate';   -- 'rotate' | 'mirror'

-- 2. drive_connection: allow many per space; flag the active write target + health.
alter table drive_connection drop constraint drive_connection_space_id_key;     -- was UNIQUE(space_id)
alter table drive_connection add column is_active boolean not null default true; -- current rotate target
alter table drive_connection add column status text not null default 'active';   -- 'active' | 'full' | 'error'

-- 3. drive_folder: cache folder ids per connection (each Drive has its own tree).
alter table drive_folder add column connection_id uuid;
update drive_folder f set connection_id = dc.id
  from drive_connection dc where dc.space_id = f.space_id;
delete from drive_folder where connection_id is null;
alter table drive_folder alter column connection_id set not null;
alter table drive_folder add constraint drive_folder_connection_id_fkey
  foreign key (connection_id) references drive_connection(id) on delete cascade;
alter table drive_folder drop constraint drive_folder_space_id_path_key;         -- was UNIQUE(space_id, path)
alter table drive_folder add constraint drive_folder_connection_id_path_key unique (connection_id, path);

-- 4. document_sync: a document may live in several Drives, so key it by connection.
alter table document_sync add column connection_id uuid;
update document_sync s set connection_id = dc.id
  from document d
  join drive_connection dc on dc.space_id = d.space_id
  where s.document_id = d.id and s.target = 'google_drive';
delete from document_sync where connection_id is null;
alter table document_sync alter column connection_id set not null;
alter table document_sync add constraint document_sync_connection_id_fkey
  foreign key (connection_id) references drive_connection(id) on delete cascade;
alter table document_sync drop constraint document_sync_pkey;                    -- was (document_id, target)
alter table document_sync add constraint document_sync_pkey primary key (document_id, connection_id);
