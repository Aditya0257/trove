-- =============================================================================
--  V5 — backup observability
-- =============================================================================
--  Purpose:        a log of backup runs (pg_dump, drive sync, mirror, export).
--  Business use:    the core principle is "lose the host, lose ZERO documents";
--                  this table is how we PROVE the backup fan-out actually ran.
--  Design:         verbatim from DESIGN.md §2 (V5 block).
--  Slice status:   table created now; the backup jobs are a later phase (§5).
-- =============================================================================

create table backup_run (
    id         uuid primary key default gen_random_uuid(),
    kind       text not null,              -- pg_dump | drive_sync | mirror | export
    status     text not null,              -- running | success | failed
    location   text,                       -- where the artifact landed
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    detail     text
);
