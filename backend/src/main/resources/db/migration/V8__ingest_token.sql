-- =============================================================================
--  V8 — per-space ingest tokens (forward-to-file addresses)
-- =============================================================================
--  Purpose:        one unguessable token per space, so a forwarded email/WhatsApp
--                  can be routed to the right space without a shared secret + spaceId.
--  Business use:    each space gets its own ingest address (token embedded), e.g.
--                  trove+<token>@ingest.<domain>; forwarding there files into that space.
--  Design:         one row per space (space_id PK). token is unique + unguessable.
-- =============================================================================

create table ingest_token (
    space_id   uuid primary key references space(id) on delete cascade,
    token      text not null unique,
    created_at timestamptz not null default now()
);
