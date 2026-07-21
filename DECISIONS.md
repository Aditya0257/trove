# Trove — Decision Log (living document)

This file records every decision made while building Trove that **maps to, refines,
or deviates from** the product brief, `DESIGN.md`, or the project requirements. It
is append-only in spirit: we never delete the original wording in those docs —
instead we add an inline **"Build note"** next to the original and cross-reference it
here.

Why this file exists: the brief calls the app disposable but the *data* and the
*reasoning* precious. The reasoning behind a change is as valuable as the change.
Keeping it live-documented means anyone (including future-you) can see not just
*what* the code does but *why it diverged from the design*, without archaeology.

Format for each entry:
- **ID** — stable handle (D1, D2, …) referenced from inline Build notes.
- **Decision** — what we chose.
- **Original text** — what the design/brief said (kept verbatim in its file too).
- **Why** — the reasoning.
- **Touches** — files annotated with a Build note pointing here.
- **Status** — active | superseded.

---

## D1 — Storage impl named `S3StorageService` (not `R2StorageService`)

- **Decision:** The concrete `StorageService` implementation is called
  `S3StorageService`.
- **Original text:** `DESIGN.md` §3 says *"`storage` — **`StorageService` (interface)**
  with `R2StorageService` impl (MinIO in dev)."*
- **Why:** One class serves **both** environments — Cloudflare R2 in prod and MinIO
  in dev — because both speak the S3 API and differ only by endpoint/credentials
  (config, not code). Naming it after one provider (`R2…`) misleads a reader into
  thinking it is R2-specific; `S3StorageService` states the truth (it is an
  S3-protocol client) and keeps the door open for any S3-compatible backend. The
  interface stays `StorageService`, so nothing else in the design changes.
- **Touches:** `DESIGN.md` §3, `DESIGN.md` §6.1.
- **Status:** active.

## D2 — Compile to Java 21 (per brief); runs on the locally installed JDK 25

- **Decision:** `pom.xml` targets Java **21** (`<java.version>21</java.version>`,
  `--release 21`). The dev machine currently has **JDK 25**, which compiles and runs
  Java-21 bytecode fine.
- **Original text:** product brief → *"Backend: Spring Boot (Java 21)"* and
  *"Java 21"* throughout.
- **Why:** The brief pins Java 21 (Oracle Always Free ARM + Spring Boot LTS
  baseline). We honor that as the compile target so the artifact is portable to a
  real JDK-21 host. JDK 25 is backward compatible, so no toolchain install is forced
  on the developer right now. If runtime issues appear on JDK 25 (Spring/Hibernate
  proxy generation occasionally lags the newest JDK), installing JDK 21 via SDKMAN
  is the clean fix — noted in `README.md`.
- **Touches:** product brief (tech stack), `README.md`.
- **Status:** active.

## D3 — Production async extraction: bounded executor + AFTER_COMMIT event + reconciler

- **Decision:** Extraction runs asynchronously via a **dedicated bounded
  `ThreadPoolTaskExecutor`**, triggered by a
  **`@TransactionalEventListener(phase = AFTER_COMMIT)`**, with an
  **`ExtractionReconciler`** (startup `ApplicationRunner` + periodic `@Scheduled`
  sweep) that re-dispatches any document whose extraction never completed.
- **Original text:** `DESIGN.md` §4.1 step 5: *"Async: `ExtractionService.extract(key)`
  → provider returns fields → … update the row + rewrite the sidecar."*
- **Why:** The design says "async" but not *how*. A naive `@Async` fire-and-forget
  can (a) race the still-uncommitted upload transaction and (b) silently lose work
  if the host dies mid-task — both violate the core principle ("losing the host must
  lose ZERO documents"). The chosen pattern fixes both: AFTER_COMMIT guarantees the
  row exists before the async thread reads it; the reconciler gives **at-least-once**
  processing so a reclaimed Oracle host simply re-extracts on restart. Bounded
  executor + `CallerRunsPolicy` applies backpressure instead of dropping or
  OOM-ing. No Kafka/queue infra needed — consistent with "don't over-engineer."
- **Touches:** `DESIGN.md` §4.1.
- **Status:** active.

## D4 — Files are stored under a provisional `uncategorized/` path; category is filled in after extraction

- **Decision:** At upload time (before extraction) the object key uses category code
  `uncategorized`: `uncategorized/{yyyy-MM}/{slug}-{shortId}.{ext}`. The **sidecar
  JSON and DB row** are updated with the real category once async extraction resolves
  it. The physical object is **not** relocated in Slice 1.
- **Original text:** `DESIGN.md` §6.1 key scheme
  `{categoryCode}/{yyyy-MM}/{slug}-{shortId}.{ext}`; §4.1 stores the file (step 3)
  *before* extraction resolves the category (step 5).
- **Why:** There is an inherent ordering: the design stores the file first, but the
  category is only known after extraction. Something must fill `{categoryCode}` at
  store time. `uncategorized` is that honest placeholder. The **truth of the
  category lives in the sidecar + DB**, which are updated post-extraction, so the
  vault is still self-describing. Physically moving objects on re-categorization
  (copy+delete+re-key) is real work with a consistency window; it is deferred as a
  later enhancement rather than risked in Slice 1. The human-navigable *folder* tree
  is Tier 3 (Google Drive, a later phase) and can be organized from the sidecar
  regardless of the R2 key.
- **Touches:** `DESIGN.md` §6.1, `DESIGN.md` §4.1.
- **Status:** active.

## D5 — `extraction_confidence IS NULL` is the "extraction pending" sentinel (no schema change)

- **Decision:** The reconciler and idempotency checks treat a document with
  `extraction_confidence IS NULL` as "not yet extracted." No new column is added.
- **Original text:** `DESIGN.md` §2 `document` table — has `status`
  (needs_review | confirmed) and `extraction_confidence numeric(4,3)`, but **no**
  dedicated extraction-state column.
- **Why:** The DDL in `DESIGN.md` §2 is the fixed schema we must create exactly
  (Flyway owns it, Hibernate only validates). Rather than invent a column the design
  never sanctioned, we reuse an existing signal: a freshly inserted row has a null
  confidence until the provider fills it. This makes crash recovery a one-line query
  (`WHERE extraction_confidence IS NULL`) and keeps extraction **idempotent** — once
  confidence is set, it won't be re-run. If a richer extraction lifecycle is needed
  later, that's a future migration + design update, logged here.
- **Touches:** `DESIGN.md` §2 (document table).
- **Status:** active.

## D6 — Minimal auth for Slice 1 via a seeded dev user + personal space (Flyway V6)

- **Decision:** Flyway migration `V6` seeds one `app_user`, their `personal` space,
  and the global system `category` rows, all with fixed UUIDs. The API defaults to
  these when no auth context is present.
- **Original text:** requirements — *"Keep authentication minimal for now (a single
  seeded user and one personal space is fine)…"*; `DESIGN.md` §5 phase 1 is full
  identity/spaces/auth.
- **Why:** Slice 1 is the upload→store→extract→list→confirm vertical. Full auth is a
  later phase. Seeding fixed IDs lets every endpoint operate against a real space and
  user without a login flow, and makes requests reproducible. Real auth (JWT,
  register/login, membership checks) slots in later without touching the storage or
  extraction code.
- **Touches:** `DESIGN.md` §5.
- **Status:** active.

## D7 — No Lombok; explicit code with structured file/method headers

- **Decision:** No Lombok. Entities/services are written out explicitly; every file
  opens with a header comment (Purpose · Business use case · Solution architecture ·
  Design · Reasoning & logic) and every non-trivial method carries a doc comment
  explaining the *why*.
- **Original text:** n/a — no design doc mandates Lombok; this is a project coding
  convention.
- **Why:** Goal is production-grade, self-documenting code where the rationale for a
  file lives at the top of that file, so a future rename/refactor has all the context
  in one place. Lombok's generated code hides intent and adds a build-time agent;
  explicit code is clearer to read and review.
- **Touches:** all backend source files.
- **Status:** active.

## D8 — Live parallel documentation: inline Build notes + this log (never erase originals)

- **Decision:** Every decision above is mirrored as an inline `> **Build note (Dxx):**`
  blockquote placed next to the original text in the relevant doc. Originals are kept
  verbatim.
- **Why:** Preserving the original wording next to each change keeps the design's
  intent legible while making the deviation and its reasoning discoverable exactly
  where a reader would look.
- **Touches:** `DESIGN.md`, `README.md`.
- **Status:** active.

## D9 — Extraction is a provider-agnostic fallback chain, free-tier first

- **Decision:** Real extraction (Slice 2) is not a single provider. An
  `ExtractionEngine` walks an ordered, configurable **chain** of `{provider, model,
  effort}` steps and returns the first result that passes an acceptance gate
  (confidence ≥ threshold). A per-step **circuit breaker** skips a step for a
  cooldown after quota/rate-limit errors. The `ExtractionProvider` interface gains a
  model/effort-aware overload (the documented `extract(bytes, mime)` stays, via a
  default method). Providers implemented: `GeminiExtractionProvider`,
  `OllamaExtractionProvider`, and the existing `StubExtractionProvider` (guaranteed
  last resort). Which provider actually ran is recorded in `document.extra`.
- **Original text:** `DESIGN.md` §6.2 — *"`VisionExtractionProvider` (later): send
  the file to a vision LLM … Selected via the `extraction.provider` config property;
  no other code changes."* And build order item 2: *"Real extraction provider (swap
  the stub)."*
- **Why:** The product must run at **zero cost** for ~100–150 users for years on
  free tiers. No single free tier is reliable long-term (quotas expire, limits
  change), so binding to one provider is a liability. A chain gives: free-first
  routing (Gemini free tier → Cloudflare Workers AI → local Ollama → stub),
  automatic failover across models *and* providers, backpressure against exhausted
  quotas (circuit breaker), and a guaranteed-complete pipeline (stub never fails).
  Because every result still lands in `needs_review`, a weaker fallback tier is
  acceptable — a human confirms. This generalizes the design's single
  `extraction.provider` switch into `extraction.chain` without changing the pipeline,
  storage, or review code.
- **Touches:** `DESIGN.md` §6.2.
- **Status:** active.

## D10 — Auth is stateless JWT + BCrypt; access control is per-space membership

- **Decision:** Slice 3 uses **JWT (HS256) + Spring Security + BCrypt**. Register/login
  are the only public endpoints; every other endpoint requires a Bearer token. The
  token carries the user id; **space roles are looked up per-space from
  `space_member`, not baked into the token**. A single `SpaceAuthorization` gate
  enforces read (any member), write (owner/member), and admin (owner) on every
  document/space operation. Registration also provisions the user's personal space.
  The seeded dev user is given a real BCrypt login on startup by
  `DevAccountInitializer` (only while it holds the placeholder hash; disabled when
  `trove.dev.default-password` is blank). Document endpoints no longer take a
  dev-default user/space — they use the authenticated user and default to that
  user's personal space.
- **Original text:** `DESIGN.md` §3 — *"`auth` — … Register/login, issue JWT, resolve
  current user."* and *"`space` — … every document/query is checked against
  membership + role. This is where multi-user access control lives."* Supersedes the
  Slice-1 shortcut recorded in D6 (seeded dev user with no login).
- **Why:** Stateless JWT fits the disposable/redeployable host (any instance
  validates a token with just the shared secret — no session store). BCrypt is the
  standard for password storage. Keeping roles out of the token means a role change
  takes effect immediately (no stale-token window) and one user can hold different
  roles in different spaces. Centralizing checks in `SpaceAuthorization` prevents
  accidental cross-space data leaks.
- **Touches:** `DESIGN.md` §3, `DECISIONS.md` → D6 (dev-login now real), `README.md`.
- **Status:** active.

## D11 — Spend tracking aggregates CONFIRMED documents only

- **Decision:** Slice 4 adds spend tracking (`/api/spend/by-category`, `/by-month`,
  `/summary`) via native aggregate queries in `AnalyticsRepository`, summing
  `document.amount` grouped by category and by `YYYY-MM`, scoped to a space and date
  range. **Only `status = 'confirmed'` documents are counted.**
- **Original text:** `DESIGN.md` §3 — *"`analytics` — `AnalyticsService`: spend by
  category/period, and anomaly detection …"* (anomaly detection remains a later
  phase).
- **Why:** The core principle is that extracted numbers are never trusted until a
  human confirms them. Spending totals must therefore be built only from verified
  amounts — otherwise a misread bill would corrupt the numbers. Native SQL is used
  because the aggregation joins document→category on a plain FK and buckets by month
  with `to_char`; only members may read (SpaceAuthorization). This is also the data
  foundation the later anomaly detector (build order item 6) will compare against.
- **Touches:** `DESIGN.md` §3.
- **Status:** active.

## D12 — Reminders: scheduled dispatch + auto-create from confirmed due dates

- **Decision:** Slice 5 adds reminders (`due` / `renewal` / `warranty_expiry`). A
  `ReminderScheduler` (`@Scheduled` fixed delay) calls `ReminderService.dispatchDue`,
  which "sends" (logs, for now) and marks `sent` every pending reminder whose
  `remind_on <= today`. A `due` reminder is auto-created when a document is
  **confirmed** with a due date — fired `lead-days` (default 3) before the due date —
  via a `DocumentConfirmedEvent` (AFTER_COMMIT), keeping documents and reminders
  decoupled. Manual create/list/dismiss endpoints under `/api/reminders`, space-scoped.
- **Original text:** `DESIGN.md` §3 — *"`reminder` — `ReminderService` +
  `ReminderScheduler` (`@Scheduled`) scanning `reminder.remind_on` and dispatching
  notifications."*
- **Why:** Auto-creating only from **confirmed** due dates upholds "never trust
  extracted numbers/dates until a human confirms." The event keeps the reminder
  feature from coupling into `document`. **Implementation note:** the auto-create runs
  in the AFTER_COMMIT listener, where a plain `@Transactional` save flushes but never
  commits (the row silently vanishes) — found in live testing and fixed with
  `Propagation.REQUIRES_NEW`. Real notification channels (email/WhatsApp) are a later
  phase; this slice delivers the scheduling mechanism.
- **Touches:** `DESIGN.md` §3.
- **Status:** active.

## D13 — Anomaly detection: trailing category average, evaluated at confirm

- **Decision:** Slice 6 flags a confirmed bill as an anomaly when its amount exceeds
  the **trailing average of prior confirmed documents in the same category** (within
  `lookback-months`) by at least `threshold-pct` (default 40%), requiring at least
  `min-samples` (default 3) of history. The verdict is computed at **confirm** time
  by `AnomalyService` and stored on the document under `extra.anomaly`
  ({anomaly, amount, average, deltaPct, sampleCount, thresholdPct, enoughHistory}).
  `GET /api/anomalies` lists flagged documents (native jsonb filter).
- **Original text:** `DESIGN.md` §3 — *"`analytics` — `AnalyticsService`: … and
  anomaly detection (e.g. this bill vs trailing average for the same
  merchant/category)."*
- **Why:** Comparing to confirmed history keeps the baseline trustworthy (same rule
  as spend, D11). Evaluating at confirm means the flag is computed once, travels in
  the self-describing sidecar, and needs no recomputation to display. `min-samples`
  prevents false alarms on the first bills (correctly shown as `enoughHistory:false`
  in testing). Category is used as the grouping for the headline use case
  ("electricity higher than usual"); a merchant-level variant can be added the same
  way. Stored in `extra` (jsonb) to avoid a schema change (consistent with D5).
- **Touches:** `DESIGN.md` §3.
- **Status:** active.

## D14 — Natural-language search is rule-based (no LLM), behind a swappable parser

- **Decision:** Slice 7 adds search: a structured filter API and a natural-language
  endpoint. NL is handled by a **rule-based `NaturalQueryParser`** (category
  synonyms, month/year and relative periods, "last"/"all", leftover words → free
  text) that produces a `SearchQuery`; both paths run through the same
  Specification-based query. Free text matches raw OCR text, filename, OR a resolved
  merchant. The natural endpoint returns the interpreted filters for transparency.
- **Original text:** `DESIGN.md` §3 — *"`search` — `SearchService`: structured
  filters + a natural-language layer ("all Nike purchases", "last water bill") mapped
  to query params."*
- **Why:** A rule-based parser is **free and instant** — no LLM cost or latency — and
  fully covers the design's example queries at Trove's scale, fitting the zero-cost
  goal. It's isolated behind its own class so an LLM-backed parser could replace it
  later with no change to `SearchService` (same pluggable philosophy as extraction,
  D9). Returning the interpretation keeps the feature debuggable and trustworthy.
- **Touches:** `DESIGN.md` §3.
- **Status:** active.

## D15 — Backup/DR: export ZIP, import, sidecar rebuild, pg_dump (mirror/Drive later)

- **Decision:** Slice 8 delivers the provider-independent safety net: an on-demand
  **export ZIP** (`manifest.json` + `data.csv` + `files/` with originals + sidecars),
  **import** (restore files to their keys, then rebuild rows), **DR rebuild** that
  reconstructs document rows straight from bucket sidecars, and a **pg_dump** job
  (on-demand + opt-in schedule) that uploads the snapshot to object storage. All log
  to `backup_run`. Import and DR share one faithful, idempotent restore path (from
  sidecars). Admin-only ops (import/rebuild/pg-dump/runs) are gated to the seeded dev
  user until a full role model exists. The **second-cloud mirror and Google Drive
  sync are deferred** (need external accounts) with clear seams.
- **Original text:** `DESIGN.md` §3 `backup` module and §4.3/§4.4/§4.5 (backup
  fan-out, export/restore, DR rebuild).
- **Why:** These are the pieces that make "lose the app + DB + host, lose ZERO
  documents" real *and testable now* with no external provider. DR-from-sidecars is
  the truest restore (original ids/fields), which is why import reuses it. pg_dump is
  stored in the same durable bucket so one place holds files + a DB restore point.
- **Sidecar enrichment:** `DocumentSidecar` gained `sidecarKey`, `mimeType`,
  `sizeBytes`, `originalFilename`, `vital`, and `extractionConfidence` beyond the
  fields shown in `DESIGN.md` §6.1, so a row can be rebuilt *faithfully* from the
  sidecar alone. Additive and backward-compatible (older sidecars read with defaults).
- **Known limitation:** line items are not in the sidecar, so DR rebuild does not
  restore them (documents + all header fields are restored). A future enhancement can
  add line items to the sidecar.
- **Touches:** `DESIGN.md` §3, `DESIGN.md` §6.1 (sidecar shape).
- **Status:** active.

## D16 — Forward-to-file ingestion reuses the upload pipeline via a byte adapter

- **Decision:** Slice 9 adds `/api/ingest/email` and `/api/ingest/whatsapp` (with
  the Meta GET verification handshake). Both are **public** (permitted in
  SecurityConfig) but gated by a **shared secret** (`trove.ingest.secret`). Forwarded
  bytes are wrapped in a `ByteArrayMultipartFile` and pushed through the **exact same**
  `DocumentService.upload` pipeline (dedupe, store + sidecar, async extraction,
  needs_review). The document is attributed to the target space's owner.
- **Original text:** `DESIGN.md` §3 `ingestion` — *"`EmailIngestController` /
  `WhatsAppWebhookController`: accept a forwarded document and route it through the
  same `DocumentService` pipeline."*
- **Why:** Reusing the pipeline means forwarded documents get identical treatment
  (dedupe, review, extraction chain) for free — no parallel code path to drift. A
  shared secret is the minimal viable gate for public webhooks; per-space ingest
  addresses/tokens and true sender→user mapping are later refinements. For WhatsApp,
  media is taken inline here for a working/testable flow; a production integration
  receives a media id and fetches the bytes first (the seam), then calls the same
  service. Missing/invalid token → 401 (fixed after live testing showed a missing
  required param returned 500).
- **Touches:** `DESIGN.md` §3.
- **Status:** active.

## D17 — Google Drive backup via per-space-owner OAuth (NOT a service account)

- **Decision:** The Google Drive backup leg uses **OAuth per space owner**: each owner
  authorizes Trove (scope **`drive.file`**, `access_type=offline`, `prompt=consent`)
  and the encrypted refresh token is stored per space. `DriveSyncJob` builds
  `Trove/{categoryCode}/{yyyy-MM}/` in that owner's Drive and uploads each document,
  idempotently (cached folder ids in `drive_folder`; per-doc `document_sync`).
- **Original text:** `DESIGN.md` §1/§4.3 — Tier-3 Google Drive, "human-navigable",
  scheduled Drive sync (previously deferred in D15).
- **Why — service accounts don't work here:** a Google **service account has 0 GB of
  Drive quota** and cannot own/store files in a normal Drive, so it literally cannot
  hold backups. **Per-owner OAuth** instead writes into each user's **own 15 GB free,
  permanent** Drive — for ~100 users that's ~1.5 TB of free durable backup with no
  central quota to exhaust, and no "trial." Scope `drive.file` is least-privilege: the
  app can only see/manage the folders and files **it created**, so we cache folder ids
  locally rather than listing the user's Drive. This is the correct zero-cost,
  scalable design for the stated audience.
- **Config:** `google.oauth.client-id/secret/redirect-uri` (env). State is an
  AES-GCM-signed space id (stateless, no server-side state table).
- **Touches:** `DESIGN.md` §1, §4.3, `DECISIONS.md` → D15 (un-defers Drive).
- **Status:** active.

## D18 — Encryption at rest via a single AES-256-GCM service (seam for vital docs)

- **Decision:** Added `EncryptionService` (AES-256-GCM; key derived by SHA-256 from
  `TROVE_ENCRYPTION_KEY`). Used now to encrypt the Drive **refresh token** at rest.
- **Original text:** `CLAUDE.md` — *"Vital documents … encrypt at rest. Decide this at
  the storage layer, not as an afterthought."*
- **Why:** Secrets/PII must not sit in plaintext. GCM gives confidentiality +
  integrity in one primitive; a random IV per value is stored alongside the
  ciphertext (self-describing). This is deliberately the **same seam** the brief's
  vital-document encryption will reuse. **TODO (open):** wire vital-document *file*
  bytes through this service at the storage layer (encrypt on `store`, decrypt on
  `get`, keyed off `document.is_vital`) — not yet implemented; only the token uses it
  today.
- **Touches:** `CLAUDE.md` (vital docs), `DECISIONS.md` → D17.
- **Status:** active (token) — vital-doc file encryption now implemented in **D21**.

## D19 — Second-cloud mirror to Backblaze B2 (S3-compatible), key-diff copy

- **Decision:** Added a `MirrorService`/`MirrorJob` that copies every primary object
  (files + sidecars + dumps) to an **independent second cloud** configured via
  `trove.mirror.*`. Default target is **Backblaze B2** (S3-compatible). Copy is a
  key-listing diff (only new/missing keys), idempotent, on-demand (`POST
  /api/admin/mirror`, admin) + scheduled, logged to `backup_run`.
- **Original text:** `DESIGN.md` §1/§4.3 — "mirror the R2 bucket to a second
  provider" (previously deferred, D15).
- **Why:** An independent-provider copy means a single provider outage or account
  loss can't wipe the vault (core principle). **B2 is chosen** because its free tier
  is genuinely permanent (10 GB storage + 1 GB/day egress), not a trial, and it
  speaks the S3 API — so the mirror **reuses the same AWS S3 SDK**, only endpoint/
  keys/bucket differ (no new storage code). Verified locally against a second MinIO
  bucket (copied 47 objects, re-run skipped all).
- **Touches:** `DESIGN.md` §1, §4.3, `DECISIONS.md` → D15.
- **Status:** active (mechanism); point at B2 by setting `trove.mirror.*`.

## D20 — Per-space ingest addresses (unguessable token routes to a space)

- **Decision:** Each space can mint an unguessable **ingest token** (`ingest_token`
  table, V8); the email/WhatsApp webhooks accept it and route the forwarded document
  to that space with **no shared secret + spaceId** needed. Owner endpoints get/rotate
  the token and render the address `trove+<token>@<domain>`. The old shared-secret +
  spaceId path still works (backward compatible).
- **Original text:** `DECISIONS.md` → D16 noted per-space ingest addresses as a later
  refinement of the shared-secret webhook.
- **Why:** A per-space address is how forward-to-file actually works in practice —
  you give each space (household, project) its own address to forward to, and rotate
  it if it leaks, without a central shared secret. Verified: address minting,
  token-only ingest (202), rotation invalidating the old token (401), and
  backward-compatible shared-secret ingest (202).
- **Touches:** `DECISIONS.md` → D16.
- **Status:** active.

## D21 — Vital documents are encrypted at rest; served via a decrypt-stream path

- **Decision:** Documents flagged **vital** (passport/Aadhaar/PAN/policies) have their
  file bytes **AES-256-GCM encrypted in object storage** (reusing D18's
  EncryptionService). Vital is set at **upload** (`vital=true` → encrypt at store) or
  at **confirm** (transition re-encrypts/decrypts the stored object in place). A
  `document.encrypted` column (Flyway V9) tracks state. Because encrypted objects
  can't be handed out as presigned URLs (client would get ciphertext), vital docs are
  served via **`GET /api/documents/{id}/content`** (backend decrypts and streams);
  non-vital keep fast presigned URLs. `file_hash`/`size_bytes` describe the plaintext
  (so dedupe + display are stable). The `encrypted` flag rides in the sidecar, so DR
  rebuild restores it faithfully.
- **Original text:** `CLAUDE.md` — *"Vital documents … encrypt at rest. Decide this at
  the storage layer, not as an afterthought."* Resolves the TODO left open in D18.
- **Why:** Targeted encryption (only vital) matches the brief and keeps the common
  path fast (presigned, no proxying). Storing the plaintext hash keeps content dedupe
  working across encrypted/plaintext. A consequence (by design): vital files in the
  Drive/mirror copies are ciphertext — arguably desirable for the most sensitive docs.
  Verified live: vital upload stores ciphertext, `/content` decrypts to the exact
  original, sidecar shows `encrypted=true`, confirm-time vital transition re-encrypts
  in place, non-vital still presigned.
- **Trade-off:** encryption is keyed by `TROVE_ENCRYPTION_KEY` — losing that key makes
  vital files unrecoverable (expected property of encryption at rest; key must be
  backed up out-of-band).
- **Touches:** `CLAUDE.md` (vital docs), `DECISIONS.md` → D18.
- **Status:** active.

## D22 — Workers AI provider hardening after live verification (model, input shape, response shape, license gate)

- **Decision:** Verifying the real Cloudflare Workers AI free tier end-to-end surfaced
  four things, now fixed so the provider is genuinely swappable/correct:
  1. **Default vision model → `@cf/meta/llama-3.2-11b-vision-instruct`** (was
     `@cf/llava-hf/llava-1.5-7b-hf`). LLaVA-1.5-7b *hallucinated* whole documents
     (invented an electricity bill for a grocery receipt); Llama-3.2-Vision reads
     real receipts/bills accurately.
  2. **Model-aware request body.** LLaVA wants a flat `{image:[uint8…], prompt}`;
     Llama-Vision (and other instruct vision models) want the portable OpenAI-style
     `{messages:[{content:[text, image_url(data-URI)]}]}` — the flat form returns
     "Unable to add image…". `CloudflareExtractionProvider.buildRequestBody` branches
     on the model name so a future swap needs no code change.
  3. **`result.response` can be a JSON OBJECT, not a string.** In JSON/structured
     mode both the vision model *and* the search text model return the object directly
     under `result.response`; `.asText()` yielded `""` → "Empty model response" /
     "No JSON in LLM response". Both `CloudflareExtractionProvider` and
     `LlmQueryParser` now re-serialize an object/array response before parsing.
  4. **One-time Meta license gate.** Llama models 403 with "Model Agreement" until you
     POST `{"prompt":"agree"}` once per account (documented in `docs/DEPLOYMENT.md`).
- **Original text:** `DECISIONS.md` → D9 (extraction chain, "Cloudflare Workers AI"),
  D14/its successor (LLM search), `CLAUDE.md` — *"add a real vision model provider
  behind the same interface."* Originals unchanged; this is the live-verification refinement.
- **Why:** The brief demands providers be swappable and the human-review step never be
  trusted blindly. Real testing proved the *plumbing* was correct but the model choice
  and the exact Workers AI request/response contract were not — the kind of thing only
  a live call reveals. Confidence normalization (`ExtractionResponseParser` clamps a
  stray `confidence:100` → `1.0`) already handled the model's percentage-scale quirk.
  Verified live against real R2 + Workers AI + B2 + Neon: extraction reads
  "RELIANCE FRESH SUPERMARKET / 735 INR" into `needs_review`; NL search parses
  "most expensive shopping bills" → `category=shopping, sortBy=amount desc`; B2 mirror
  copies real objects.
- **Also:** `.env` must be loaded **literally** (not `source`d) — an unquoted `&` in
  the Neon URL aborts `source` mid-file and silently unsets everything after it. Guidance
  updated in `.env.example` (matches how systemd/Docker read the file). 
- **Touches:** `DECISIONS.md` → D9, D14; `CloudflareExtractionProvider`, `LlmQueryParser`,
  `application.yml`, `.env.example`, `docs/DEPLOYMENT.md`.
- **Status:** active.

## D23 — The Notice System: two-channel, non-hiding feedback across API + web + mobile

- **Decision:** Every meaningful outcome (errors, and async results like extraction
  fallbacks) carries a **two-channel `notice`**, never a bare error string:
  - `userMessage` — calm, human, actionable ("Auto-fill paused for today — add the
    fields from your photo; everything else works").
  - `devNote` — precise technical cause, **never any secret** (providers, counts,
    timings, request id: yes; keys/tokens/endpoints/buckets: never).
  - plus `level` (info|success|warning|error), `code` (stable machine code, e.g.
    `EXTRACTION_QUOTA`, `DUPLICATE_DOCUMENT`), and a free-form `meta` map.
  The philosophy is **dignify errors, don't hide them** — the audience is the owner +
  friends/family, so surfacing the "why" beautifully is a feature, not a leak.
- **Contract (backend):**
  - `ApiNotice` record embedded in `ApiError` — every error response now includes a
    notice built per exception type in `ApiExceptionHandler`.
  - `extra.extractionMeta` on documents — the full chain attempt trail
    (`[{label, provider, model, status, reason, confidencePct, latencyMs}]`), a
    `fellBack` flag, and a derived `notice`. The flat `extractionProvider/Model/
    Accepted` fields stay for back-compat.
  - A servlet filter stamps `X-Trove-Request-Id` + `X-Trove-Duration-Ms` on every
    response (and MDC for logs) so clients can correlate + show per-request timing.
- **Surfaces (clients):**
  - **Web:** a two-channel toast (user line + collapsible dev note) **and** a
    "Developer" surface = clean grouped **styled console** (`%c` CSS, monospace, muted
    palette, no emoji) per request + an in-app **Developer drawer** with recent
    requests and an AI-usage gauge. Chosen: console **+** drawer.
  - **Mobile (Flutter):** the same two-channel snackbar/dialog + a Developer drawer
    screen. Built into the Flutter API client from line one (this is why Flutter is
    built *after* this contract).
- **Why:** One contract, both clients light up identically; the app becomes
  legible and interactive instead of opaque. Aligns with the human-review principle
  (when auto-fill can't run, tell the user *plainly* and let them fill it in).
- **Honest scope:** per-request info (provider, fallback reason, latency, confidence)
  ships now. A true **daily Neuron gauge** needs a small poll of Cloudflare's analytics
  API (not returned per-request); it plugs into the same drawer as a fast-follow,
  labeled "estimated" until wired exactly. Per-request **token** counts (from Workers
  AI `result.usage`) are a cheap add planned into `extractionMeta`.
- **Touches:** `DECISIONS.md` → D9, D22; `common/error/*`, `common/notice/*`,
  `common/security/SecurityNoticeHandler`, `common/web` request filter, `extraction/*`;
  Angular web (`core/notice/*`, interceptor, toast + Developer drawer); Flutter client
  (`core/notice/*`, notice-aware `ApiClient`, toast + Developer drawer).
- **Status:** active — implemented across backend, web, and mobile. Fast-follow: a live
  daily-Neuron usage gauge (Cloudflare analytics poll) + per-request token counts.
