-- =============================================================================
--  V2 — categories & merchants
-- =============================================================================
--  Purpose:        the filing taxonomy (category) and normalized merchant identity
--                  (merchant + merchant_alias for mapping raw OCR names).
--  Business use:    "auto-file into electricity/water/…" and "all Nike purchases"
--                  both need stable categories and a canonical merchant per vendor.
--  Design:         verbatim from DESIGN.md §2 (V2 block).
--  Note:            category.space_id null = global/system category (shared by all).
-- =============================================================================

create table category (
    id       uuid primary key default gen_random_uuid(),
    space_id uuid references space(id) on delete cascade,  -- null = global/system
    code     text not null,                                -- 'electricity', 'water', ...
    label    text not null,
    unique (space_id, code)
);

create table merchant (
    id             uuid primary key default gen_random_uuid(),
    canonical_name text not null unique,   -- 'Amazon'
    created_at     timestamptz not null default now()
);

create table merchant_alias (
    id          uuid primary key default gen_random_uuid(),
    merchant_id uuid not null references merchant(id) on delete cascade,
    alias       text not null unique       -- 'AMAZON PAY', 'amzn', 'Amazon.in'
);
