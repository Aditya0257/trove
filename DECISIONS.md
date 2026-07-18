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
