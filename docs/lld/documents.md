# LLD: Documents, Storage and Extraction

The capture-to-confirm core: how a file becomes a durable, self-describing, searchable,
human-verified document. Modules: `document`, `storage`, `extraction`, `category`,
`merchant`. Conceptual framing is in
[../architecture/05-ai-and-extraction.md](../architecture/05-ai-and-extraction.md);
this document is the implementation.

## 1. Purpose

Accept an uploaded image or PDF, store it durably with a sidecar, read its fields with a
vision model, and hold it in `needs_review` until a person confirms it. Then it is trusted:
it counts toward spend, becomes searchable, and can generate reminders.

## 2. Key classes

| Class | Role |
| --- | --- |
| `DocumentController` | REST surface: upload, list, get, file, content, confirm, delete, restore, purge, trash. |
| `DocumentService` | The lifecycle logic: validation, dedupe, store, insert, confirm, soft delete, restore, purge; publishes events; rewrites sidecars. |
| `StorageService` (interface) / `S3StorageService` | The seam over S3-compatible object storage; store bytes, read, move, delete, presign, and read/write sidecars. Named for the protocol so the same code targets R2 and MinIO (D1). |
| `SidecarFactory` / `DocumentSidecar` | Builds the immutable sidecar snapshot from a document row plus resolved category and merchant. |
| `ExtractionProvider` (interface) | One call in, structured JSON out. Implemented by the configured vision provider (Cloudflare Workers AI) and by `StubExtractionProvider`. |
| `ExtractionDispatcher` | Runs the provider fallback chain (real then stub) per configuration (D9, D22). |
| `ExtractionEventListener` / `ExtractionWorker` | Kicks off async extraction after commit; the worker runs it on a bounded pool. |
| `ExtractionReconciler` | Sweep that re-runs extraction for any document left pending after a crash. |
| `AiUsageTracker` / `NeuronRateService` | Meter and enforce the shared daily AI budget; convert tokens to neurons. |
| `CategoryService` / `MerchantService` | Resolve category codes (global plus space) and canonical merchants with aliases. |

## 3. Endpoints

See [../api/reference.md](../api/reference.md) under Documents. In short: `POST /api/documents`
(upload), `GET /api/documents` and `/{id}` (read), `/{id}/file` and `/{id}/content`
(download or decrypt-stream), `POST /{id}/confirm`, `DELETE /{id}`, `POST /{id}/restore`,
`DELETE /{id}/purge`, `GET /trash`. The list is paged server-side (`page` and `size` query
params; the total match count is returned in the `X-Total-Count` header) and ordered
newest-first with the id as a stable tiebreaker. The default listing (no category filter)
excludes the `email` category at the database level, since emails live under Mail.

## 4. Data touched

`document` (the index row), `line_item` (itemisation), `category`, `merchant`,
`merchant_alias`, and the `extra` jsonb on the document. Column detail is in the
[data model](../architecture/02-data-model.md). The file and sidecar live in object storage,
keyed by `storage_key` and `sidecar_key`.

## 5. Events

`DocumentUploadedEvent` (triggers async extraction), `DocumentConfirmedEvent` (triggers
reminder auto-creation, embedding, and the anomaly check), `DocumentTrashedEvent`,
`DocumentRestoredEvent`, `DocumentPurgedEvent` (drive the Drive copy's move to and from
`_Deleted`, and its hard delete). All are consumed `AFTER_COMMIT` so listeners never act on
a rolled-back change.

## 6. Upload flow (the core path)

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctl as DocumentController
    participant Svc as DocumentService
    participant St as S3StorageService
    participant DB as document
    participant Ev as events
    participant W as ExtractionWorker

    C->>Ctl: POST /api/documents (multipart, spaceId, vital, extract)
    Ctl->>Svc: upload(...)
    Svc->>Svc: reject non image/PDF (400); read bytes; SHA-256; dedupe
    Svc->>St: store bytes (+encrypt if vital) under uncategorized/YYYY-MM/
    Svc->>St: write sidecar JSON
    Svc->>DB: insert row (status=needs_review, confidence=null)
    Svc->>Ev: publish DocumentUploadedEvent (AFTER_COMMIT)
    Ev->>W: extract(documentId) if extract=true
    W->>W: provider chain -> {category, merchant, amount, dates, rawText, confidence}
    W->>DB: update row; rewrite sidecar
    Ctl-->>C: 201 created (needs_review)
```

Notable details:

- **Type and size validation.** Only images and PDF are accepted, enforced by content type
  with a filename-extension fallback; oversize is capped by the multipart limit (25 MB per
  file) and returns a 413. This is enforced server-side, not just hinted at in the picker.
- **Dedupe.** The SHA-256 of the plaintext bytes is computed and a document already live in the
  space with the same hash raises `DuplicateDocumentException` (409). A trashed copy does not
  block re-upload.
- **Provisional path.** The file is first stored under `uncategorized/` because the category is
  not known until extraction; the confirm step is where the durable classification settles
  (D4).
- **The pending sentinel.** `extraction_confidence IS NULL` means extraction has not finished;
  the review screen polls until it is set (D5).
- **One document per file.** The API stores exactly one document per uploaded file; the web
  client uploads a multi-file selection as separate requests, so two images become two
  documents. Extraction runs only for images and only when the caller sets `extract=true` (the
  saved "read images with AI" preference); PDFs are always stored straight to manual review.

## 7. Confirm flow

```mermaid
sequenceDiagram
    participant C as Client
    participant Svc as DocumentService
    participant DB as document
    participant An as AnomalyService
    participant Ev as events

    C->>Svc: POST /{id}/confirm {edits}
    Svc->>DB: apply edits; (re)encrypt if vital changed
    Svc->>DB: status=confirmed, reviewed_by/at
    Svc->>An: evaluate anomaly vs category trailing average
    An-->>Svc: verdict stored in extra.anomaly
    Svc->>DB: saveAndFlush; rewrite sidecar
    Svc->>Ev: publish DocumentConfirmedEvent(dueDate)
    Note over Ev: reminders auto-created, document embedded for search
```

Confirm replaces the document's `extra` wholesale with what the client sends, so clients pass
the full existing `extra` plus their edits (this is how the extraction trail, the anomaly
verdict, notes and `warrantyUntil` are preserved). The event carries the confirmed due date;
the reminder module reads the document to also pick up category (for renewal vs due typing)
and `warrantyUntil` (see [reminders.md](reminders.md)).

## 8. Soft delete, restore, purge

Deleting sets `status=deleted`, moves the R2 object to a `_trash/` prefix (`trash_key`), and
emits `DocumentTrashedEvent` so the Drive copy moves to `_Deleted`. Restore reverses it. Purge
(the daily 30-day job or an explicit action) deletes the live object, removes the row and its
dependents, and emits `DocumentPurgedEvent`. See
[../architecture/04-resilience-and-backup.md](../architecture/04-resilience-and-backup.md) for
the cross-tier lifecycle and why the B2 mirror still keeps an archival copy.

## 9. Configuration

`trove.storage.*` (endpoint, keys, bucket, presign TTL), `trove.extraction.*` (the provider
chain, image downscale threshold, budget), and the AI budget under the extraction module. Full
list in [../operations/configuration.md](../operations/configuration.md).

## 10. Edge cases

- An upload never fails because the AI failed: the provider chain falls back to the stub and
  the document is stored for manual entry (D9).
- Vital documents are encrypted before storage; the hash and size are computed on the plaintext
  so dedupe and display are stable regardless of encryption.
- A crash mid-extraction is recovered by `ExtractionReconciler`, which re-runs anything left
  with a null confidence.
- Emails are a document category of their own, filed and displayed in the Mail section rather
  than the generic bill form.
