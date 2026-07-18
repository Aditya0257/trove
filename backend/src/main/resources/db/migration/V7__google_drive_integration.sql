-- =============================================================================
--  V7 — Google Drive integration (per-space-owner OAuth backup leg)
-- =============================================================================
--  Purpose:        persist each space's Drive connection (encrypted refresh token +
--                  root folder id), a cache of created folder ids, and per-document
--                  sync state so uploads are idempotent.
--  Business use:    Tier-3 human-navigable backup into each owner's own 15 GB Drive
--                  (DECISIONS.md → D17). drive.file scope: we only touch what we made.
--  Design:         refresh_token_enc is AES-GCM ciphertext (never plaintext at rest).
--                  document_sync is generic (target column) so a 2nd-cloud mirror can
--                  reuse it later.
-- =============================================================================

create table drive_connection (
    id                uuid primary key default gen_random_uuid(),
    space_id          uuid not null unique references space(id) on delete cascade,
    refresh_token_enc text not null,            -- AES-GCM encrypted refresh token
    root_folder_id    text,                     -- Drive id of the "Trove" root folder
    connected_by      uuid references app_user(id),
    connected_at      timestamptz not null default now(),
    last_sync_at      timestamptz
);

create table drive_folder (
    id        uuid primary key default gen_random_uuid(),
    space_id  uuid not null references space(id) on delete cascade,
    path      text not null,                    -- e.g. 'electricity/2026-07' (under root)
    folder_id text not null,                    -- Drive folder id
    unique (space_id, path)
);

create table document_sync (
    document_id uuid not null references document(id) on delete cascade,
    target      text not null,                  -- 'google_drive' (extensible: 'mirror')
    external_id text not null,                  -- Drive file id
    synced_at   timestamptz not null default now(),
    primary key (document_id, target)
);
