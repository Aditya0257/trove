# Trove — Solution Architecture & Design (HLD + LLD)

This is the design the build follows. See `README.md` for the product overview and
`DECISIONS.md` for the running log of build decisions.

---

## 1. Solution architecture (HLD)

```
        ┌─────────────┐        ┌─────────────┐
        │ Angular web │        │  Flutter    │
        │   client    │        │  mobile     │
        └──────┬──────┘        └──────┬──────┘
               │  REST + JWT          │
               └──────────┬───────────┘
                          ▼
        ┌──────────────────────────────────────┐
        │      Spring Boot API (Oracle A1)      │
        │  auth · space · document · extraction │
        │  category · merchant · reminder ·     │
        │  analytics · search · backup ·        │
        │  ingestion                            │
        └───┬───────────────┬───────────────┬───┘
            │               │               │
            ▼               ▼               ▼
     ┌────────────┐  ┌──────────────┐  ┌──────────────┐
     │   Neon     │  │ Cloudflare   │  │  Extraction  │
     │ Postgres   │  │  R2 (S3 API) │  │  provider    │
     │ (index/    │  │  files +     │  │  (stub →     │
     │  metadata) │  │  sidecar JSON│  │  vision LLM) │
     └────────────┘  └──────┬───────┘  └──────────────┘
                            │  scheduled backup fan-out
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
        ┌──────────┐  ┌──────────┐  ┌──────────────┐
        │ 2nd cloud│  │ pg_dump  │  │ Google Drive │
        │ mirror   │  │ → R2+Drv │  │ human folders│
        └──────────┘  └──────────┘  └──────────────┘
```

Source of truth = the files in R2 (each with a sidecar JSON). Postgres is a
rebuildable index. The host is stateless — nothing is lost if it's reclaimed.

### Component responsibilities

- Clients (Angular / Flutter): capture or upload, review-and-confirm extracted
  fields, browse by category/space, search, see reminders and spend.
- Spring Boot API: all business logic; stateless; horizontally replaceable.
- Neon Postgres: users, spaces, document index, categories, merchants,
  reminders, tags. Metadata + extracted text only — never blobs.
- Cloudflare R2: original files + one sidecar JSON per file. The durable vault.
- Extraction provider: pluggable; returns structured fields from an image/PDF.
- Backup fan-out: mirror bucket → 2nd cloud; nightly pg_dump → R2 + Drive;
  scheduled Drive sync in a human-navigable folder tree.

---

## 2. Data model (ERD → DDL)

Entities: users, spaces + membership, documents (the core), categories,
merchants (+ aliases for normalization), line items, reminders, tags (tax
labels), and a backup-run log. Postgres DDL, owned by Flyway migrations.

```sql
-- V1: identity & spaces -----------------------------------------------------
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

-- V2: categories & merchants ------------------------------------------------
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

-- V3: documents (the core) --------------------------------------------------
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
    -- Build note (D5): a freshly inserted row has extraction_confidence = NULL,
    -- which we use as the "extraction pending" sentinel (crash-safe reconcile),
    -- avoiding a new column since this DDL is fixed. See DECISIONS.md → D5.
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

-- V4: reminders & tags ------------------------------------------------------
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

-- V5: backup observability --------------------------------------------------
create table backup_run (
    id         uuid primary key default gen_random_uuid(),
    kind       text not null,              -- pg_dump | drive_sync | mirror | export
    status     text not null,              -- running | success | failed
    location   text,                       -- where the artifact landed
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    detail     text
);
```

---

## 3. Backend modules (LLD)

Package-by-feature under `com.trove`. Key classes and the two load-bearing
interfaces:

- `auth` — `AuthController`, `UserService`, `JwtService`, `SecurityConfig`.
  Register/login, issue JWT, resolve current user.
- `space` — `SpaceController`, `SpaceService`, `SpaceAuthorization`.
  Create/join spaces; every document/query is checked against membership +
  role. This is where multi-user access control lives.
- `document` — `DocumentController`, `DocumentService`, `DocumentRepository`,
  `Document` entity. Upload, get, list-by-category, confirm review.
- `storage` — **`StorageService` (interface)** with `R2StorageService` impl
  (MinIO in dev). Methods: `store(space, file) -> keys`, `writeSidecar(...)`,
  `get(key)`, `presignedUrl(key)`, `delete(key)`. Owns the key/path scheme and
  always writes the sidecar JSON alongside the file.

  > **Build note (D1):** The concrete impl is named `S3StorageService`, not
  > `R2StorageService`. One class serves both R2 (prod) and MinIO (dev) — they
  > differ only by endpoint/credentials (config), both speak S3. See `DECISIONS.md`
  > → D1. The interface name (`StorageService`) is unchanged.
- `extraction` — **`ExtractionProvider` (interface)** returning
  `ExtractionResult{category, merchant, docDate, amount, dueDate, lineItems,
  rawText, confidence}`. Impls: `StubExtractionProvider` (first),
  `VisionExtractionProvider` (later). `ExtractionService` orchestrates and
  updates the document + sidecar.
- `category` — resolves an extracted category code to a `category` row (global
  or space-custom).
- `merchant` — `MerchantService`: normalize a raw merchant string via
  `merchant_alias`, creating a canonical `merchant` when unseen.
- `reminder` — `ReminderService` + `ReminderScheduler` (`@Scheduled`) scanning
  `reminder.remind_on` and dispatching notifications.
- `analytics` — `AnalyticsService`: spend by category/period, and anomaly
  detection (e.g. this bill vs trailing average for the same merchant/category).
- `search` — `SearchService`: structured filters + a natural-language layer
  ("all Nike purchases", "last water bill") mapped to query params.
- `backup` — `PgDumpJob`, `MirrorJob`, `DriveSyncJob` (scheduled) plus
  `ExportService` (build ZIP) and `ImportService` (restore from ZIP). Logs to
  `backup_run`.
- `ingestion` — `EmailIngestController` / `WhatsAppWebhookController`: accept a
  forwarded document and route it through the same `DocumentService` pipeline.
- `common` — DTOs, global exception handling, config (S3 client, async, CORS).

---

## 4. Key flows

### 4.1 Upload → extraction (the core path)
1. Client `POST /documents` (multipart, space id).
2. `DocumentController` computes sha-256 → `DocumentService` checks `file_hash`
   for duplicates in that space.
3. `StorageService.store(...)` writes the file to R2 and returns the keys.
4. Insert `document` row with `status = needs_review`; write the sidecar JSON.
5. Async: `ExtractionService.extract(key)` → provider returns fields →
   `category`/`merchant` resolved → update the row + rewrite the sidecar.
6. Row stays `needs_review` until a human confirms.

> **Build note (D3):** "Async" is implemented as a bounded `ThreadPoolTaskExecutor`
> triggered by a `@TransactionalEventListener(AFTER_COMMIT)` (so the async thread
> never races the still-uncommitted upload row), plus an `ExtractionReconciler`
> (startup + scheduled sweep) that re-dispatches any doc left un-extracted after a
> crash — giving at-least-once processing. See `DECISIONS.md` → D3.
>
> **Build note (D4):** Because the file is stored (step 3) *before* the category is
> known (step 5), the object is written under a provisional `uncategorized/` path;
> the sidecar + DB row are corrected once extraction resolves the category. The
> physical object is not relocated in Slice 1. See `DECISIONS.md` → D4.

### 4.2 Review → confirm
User edits/accepts extracted fields → `status = confirmed`, `reviewed_by/at`
set, sidecar updated. Nothing is trusted as final until this step.

### 4.3 Backup fan-out (scheduled)
- Nightly: `pg_dump` → gzip → upload to R2 + Drive (`backup_run` logged).
- Periodic: mirror the R2 bucket to a second provider.
- Periodic: sync files into Google Drive as `Trove/{space}/{category}/{YYYY-MM}/`.

### 4.4 Export / restore (on demand)
`ExportService` builds `vault-export-<date>.zip` = `manifest.json` +
`data.csv` + `files/`. `ImportService` reads it back and reconstitutes rows and
files — the provider-independent safety net.

### 4.5 Disaster recovery (DB lost)
Scan every sidecar JSON in R2 → rebuild all `document` rows. The DB is a cache;
the bucket is the truth.

---

## 5. Build phases

Design-first, then implement in this order (each phase shippable):

1. Identity, spaces, membership, auth.
2. Document upload + storage + sidecar (stub extraction) + list by category.
3. Real extraction provider (swap the stub); review/confirm UI.
4. Categories + merchant normalization + duplicate detection.
5. Spend tracking + anomaly detection.
6. Reminders (due/renewal/warranty) + notifications.
7. Natural-language search.
8. Backup fan-out + export/import + DR rebuild.
9. Forward-to-file ingestion (email / WhatsApp).
10. Vital-document encryption hardening.

---

## 6. Interface specs (for the two load-bearing pieces)

These two interfaces are the ones everything else depends on. Build them first,
exactly as specified.

### 6.1 StorageService

Abstracts object storage so the same code runs against local MinIO (dev) and
Cloudflare R2 (prod). It also always writes a sidecar JSON next to every file.

> **Build note (D1, D4):** Implemented by `S3StorageService` (D1). At upload time the
> category is not yet known (extraction is async), so `store(...)` is called with the
> provisional code `uncategorized` (D4); the sidecar/DB carry the real category once
> extraction completes. See `DECISIONS.md` → D1, D4.

```java
public interface StorageService {

    // Stores the raw file, returns the object key it was written under.
    // Key scheme: {categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext}
    // e.g. electricity/2026-01/reliance-a1b2c3.jpg
    StoredObject store(UUID spaceId, String categoryCode, MultipartFile file);

    // Writes/overwrites the sidecar JSON that mirrors the DB row for this file.
    // Sidecar key = same path with .json extension.
    void writeSidecar(String storageKey, DocumentSidecar sidecar);

    // Returns a short-lived signed URL the client can use to view/download.
    String presignedUrl(String storageKey, Duration ttl);

    byte[] get(String storageKey);
    void delete(String storageKey);
}

// value objects
record StoredObject(String storageKey, String sidecarKey,
                    String fileHash, long sizeBytes, String mimeType) {}
```

Sidecar JSON shape (this is what makes the bucket self-describing — the DB can
be rebuilt from these alone):

```json
{
  "documentId": "uuid",
  "spaceId": "uuid",
  "uploadedBy": "uuid",
  "storageKey": "electricity/2026-01/reliance-a1b2c3.jpg",
  "fileHash": "sha256:...",
  "category": "electricity",
  "merchant": "Reliance Energy",
  "docDate": "2026-01-14",
  "amount": 1840.00,
  "currency": "INR",
  "dueDate": "2026-01-28",
  "status": "needs_review",
  "rawText": "full OCR text ...",
  "extra": { "unitsConsumed": 312, "billingPeriod": "Dec 2025" },
  "createdAt": "2026-01-15T10:04:00Z"
}
```

### 6.2 ExtractionProvider

One call in (an image/PDF), structured fields out. Swappable: start with the
stub, add the vision model later behind the same interface.

```java
public interface ExtractionProvider {
    ExtractionResult extract(byte[] fileBytes, String mimeType);
}

record ExtractionResult(
    String categoryCode,        // 'electricity', 'shopping', 'insurance', ...
    String merchantName,        // raw, before normalization
    LocalDate docDate,
    BigDecimal amount,
    String currency,
    LocalDate dueDate,
    List<LineItemDto> lineItems,// may be empty
    String rawText,
    Map<String,Object> extra,   // type-specific fields
    BigDecimal confidence       // 0..1
) {}

record LineItemDto(String description, BigDecimal quantity, BigDecimal amount) {}
```

`StubExtractionProvider` (first, so the pipeline runs with no AI/keys): return a
fixed but realistic result — `categoryCode = "shopping"`, a sample merchant,
today's date, a small amount, one line item, `rawText = "STUB EXTRACTION"`,
`confidence = 0.5`. Every document it produces lands in `needs_review`, which is
correct — a human confirms it.

`VisionExtractionProvider` (later): send the file to a vision LLM with a prompt
that demands the exact JSON schema above, parse it into `ExtractionResult`.
Selected via the `extraction.provider` config property; no other code changes.

> **Build note (D9):** Implemented as a provider-agnostic fallback **chain** rather
> than a single provider. An `ExtractionEngine` walks an ordered list of
> `{provider, model, effort}` steps (e.g. Gemini → Cloudflare Workers AI → local
> Ollama → stub), returns the first result above a confidence threshold, and uses a
> per-step circuit breaker to skip quota-exhausted free tiers. The interface keeps
> the documented `extract(bytes, mime)` and adds a model/effort overload. Rationale:
> zero-cost operation on free tiers for years, with no lock-in. See `DECISIONS.md`
> → D9.
