-- =============================================================================
--  V19 — semantic index for "Ask your vault" (RAG over documents)
-- =============================================================================
--  Purpose:        store one embedding vector per document so a question can be
--                  answered by semantic similarity, not just keyword match.
--  Business use:    grounded natural-language Q&A over the user's own documents
--                  ("when does my passport expire?", "the fridge warranty") with
--                  citations back to the source document.
--  Design:         pgvector (already available on Neon) holds a 768-dim vector from
--                  Cloudflare's bge-base-en-v1.5. This is a REBUILDABLE INDEX like the
--                  rest of the DB — embeddings are regenerated from the source files,
--                  never a source of truth. One row per document (cascade-deleted with
--                  it). space_id is denormalised so similarity search can filter to the
--                  caller's spaces without a join. An HNSW cosine index keeps search
--                  fast; with only hundreds of rows it's cheap but future-proofs scale.
--  Cost:            bge-base ≈ 6058 neurons/M tokens → ~0.002 neurons per document, so
--                  embedding stays negligible against the 10,000 neurons/day free tier.
-- =============================================================================

create extension if not exists vector;

create table document_embedding (
    document_id uuid primary key references document(id) on delete cascade,
    space_id    uuid not null references space(id) on delete cascade,
    embedding   vector(768) not null,          -- bge-base-en-v1.5 output
    model       text not null,                 -- which model produced it (re-embed on change)
    updated_at  timestamptz not null default now()
);

-- Cosine-distance ANN index. Rebuilt automatically; safe to drop/recreate on model change.
create index idx_document_embedding_hnsw
    on document_embedding using hnsw (embedding vector_cosine_ops);

-- Filter helper: similarity search is always scoped to a space.
create index idx_document_embedding_space on document_embedding (space_id);
