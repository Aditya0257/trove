-- =============================================================================
--  V3 — documents (the core) + line items
-- =============================================================================
--  Purpose:        the central index row per stored file, plus its line items.
--  Business use:    this is what "list by category", "spend tracking", reminders,
--                  and search all read. But it is only an INDEX — the file + its
--                  sidecar JSON in object storage are the source of truth.
--  Design:         verbatim from DESIGN.md §2 (V3 block).
--  Reasoning:      extraction_confidence stays NULL until the async extractor runs;
--                  we use that as the "extraction pending" sentinel for crash-safe
--                  reconciliation (DECISIONS.md → D5). status stays 'needs_review'
--                  until a human confirms.
-- =============================================================================

create table document (
    id           uuid primary key default gen_random_uuid(),
    space_id     uuid not null references space(id) on delete cascade,
    uploaded_by  uuid not null references app_user(id),
    storage_key  text not null,          -- electricity/2026-01/reliance-jan.jpg
    sidecar_key  text not null,          -- electricity/2026-01/reliance-jan.json
    file_hash    text not null,          -- sha-256, for duplicate detection
    mime_type    text not null,
    size_bytes   bigint not null,
    original_filename text,

    category_id  uuid references category(id),
    merchant_id  uuid references merchant(id),
    doc_date     date,
    amount       numeric(12,2),
    currency     text default 'INR',
    due_date     date,

    raw_text     text,
    extra        jsonb not null default '{}'::jsonb,  -- type-specific fields
    extraction_confidence numeric(4,3),

    is_vital     boolean not null default false,      -- passport/ID/policy → encrypt
    status       text not null default 'needs_review',-- needs_review | confirmed
    reviewed_by  uuid references app_user(id),
    reviewed_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index idx_document_space     on document(space_id);
create index idx_document_category   on document(space_id, category_id);
create index idx_document_due        on document(due_date) where due_date is not null;
create index idx_document_hash        on document(file_hash);

create table line_item (
    id          uuid primary key default gen_random_uuid(),
    document_id uuid not null references document(id) on delete cascade,
    description text,
    quantity    numeric(10,2),
    unit_price  numeric(12,2),
    amount      numeric(12,2)
);
