-- =============================================================================
--  V1 — identity & spaces
-- =============================================================================
--  Purpose:        users, spaces, and membership (the access-control backbone).
--  Business use:    every document belongs to exactly one space; a space is either
--                  a user's private "personal" space or a "shared" one.
--  Design:         copied verbatim from DESIGN.md §2 (V1 block). Flyway owns this
--                  schema; Hibernate only validates against it.
--  Note:            gen_random_uuid() is built into PostgreSQL 13+ (no extension).
-- =============================================================================

create table app_user (
    id            uuid primary key default gen_random_uuid(),
    email         text unique not null,
    display_name  text not null,
    password_hash text not null,
    created_at    timestamptz not null default now()
);

create table space (
    id         uuid primary key default gen_random_uuid(),
    name       text not null,
    kind       text not null default 'personal',   -- personal | shared
    created_by uuid not null references app_user(id),
    created_at timestamptz not null default now()
);

create table space_member (
    space_id  uuid not null references space(id) on delete cascade,
    user_id   uuid not null references app_user(id) on delete cascade,
    role      text not null default 'member',       -- owner | member | viewer
    joined_at timestamptz not null default now(),
    primary key (space_id, user_id)
);
