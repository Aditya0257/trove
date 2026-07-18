-- =============================================================================
--  V4 — reminders & tags
-- =============================================================================
--  Purpose:        due/renewal/warranty reminders and tax-style tags on documents.
--  Business use:    "electricity bill due in 3 days", "80C" tax labels for export.
--  Design:         verbatim from DESIGN.md §2 (V4 block).
--  Slice status:   tables are created now (schema is owned end-to-end by Flyway),
--                  but the reminder feature itself is a later phase (DESIGN.md §5).
-- =============================================================================

create table reminder (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid references document(id) on delete cascade,
    space_id    uuid not null references space(id) on delete cascade,
    type        text not null,             -- due | renewal | warranty_expiry
    remind_on   date not null,
    status      text not null default 'pending',  -- pending | sent | dismissed
    created_at  timestamptz not null default now()
);
create index idx_reminder_due on reminder(remind_on) where status = 'pending';

create table tag (
    id       uuid primary key default gen_random_uuid(),
    space_id uuid not null references space(id) on delete cascade,
    name     text not null,                -- '80C', 'HRA', 'medical'
    unique (space_id, name)
);

create table document_tag (
    document_id uuid not null references document(id) on delete cascade,
    tag_id      uuid not null references tag(id) on delete cascade,
    primary key (document_id, tag_id)
);
