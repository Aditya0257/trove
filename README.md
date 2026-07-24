# Trove

A private vault for the documents that matter: bills, receipts, policies,
warranties, IDs. You upload a document; Trove stores it durably, reads it, files
it by category, and lets you review and confirm the extracted fields.

## Documentation

The full engineering documentation lives in [`docs/`](docs/) and renders on GitHub,
diagrams included. Start here:

- [`docs/README.md`](docs/README.md): the documentation index and a study plan.
- [`docs/architecture/00-concepts.md`](docs/architecture/00-concepts.md): the concepts
  primer (read first if any term is unfamiliar).
- [`docs/architecture/01-hld.md`](docs/architecture/01-hld.md): the High-Level Design.
- [`docs/architecture/02-data-model.md`](docs/architecture/02-data-model.md): the full
  schema, and [`docs/api/reference.md`](docs/api/reference.md): every endpoint.
- Per-module Low-Level Design in [`docs/lld/`](docs/lld/), the web client in
  [`docs/frontend/web.md`](docs/frontend/web.md), and configuration in
  [`docs/operations/configuration.md`](docs/operations/configuration.md).

A browsable HTML version of the architecture guide is in [`docs/site/`](docs/site/),
ready to serve with GitHub Pages (see below). The original design narrative and the
running decision log remain in [`DESIGN.md`](DESIGN.md) and [`DECISIONS.md`](DECISIONS.md);
[`.env.example`](.env.example) documents every config value.

### Publish the HTML guide with GitHub Pages

The Markdown docs above already render on GitHub with no setup. To also host the
designed HTML guide at a permanent public URL you own:

1. Push this repository to GitHub.
2. Settings -> Pages -> Build and deployment -> Source: **Deploy from a branch**.
3. Branch: your default branch, folder: **/docs**. Save.
4. The guide is then at `https://<user>.github.io/<repo>/site/`.

This URL is yours and needs no login or subscription. (A claude.ai artifact link, by
contrast, is private to the author's account and is only a convenient preview.)

## Status: backend complete (build order 1-9 + hardening)

The whole backend from `DESIGN.md` §5 is built, tested live, and committed:
upload → durable store + sidecar; a **multi-provider extraction chain**; **JWT auth**
+ **shared spaces/roles**; **spend tracking**; **reminders**; **anomaly detection**;
**natural-language search**; a full **backup story** (export/import ZIP, DR
rebuild-from-sidecars, pg_dump, Google Drive per-owner sync, Backblaze B2 mirror);
**forward-to-file ingestion** (email/WhatsApp + per-space addresses); and
**vital-document encryption at rest**. The **Angular web client** is built and the
**Flutter mobile app** covers the capture-first core (see
[`docs/frontend/web.md`](docs/frontend/web.md) and [`mobile/SETUP.md`](mobile/SETUP.md)).

Core vertical, for reference:

- **Upload** a file → hash it → reject duplicates in the space → store it in
  object storage **with a sidecar JSON** → insert a `needs_review` row.
- **Extraction** runs **asynchronously** and fills in category / merchant / date /
  amount, then rewrites the sidecar.
- **List** documents (optionally by category) and **confirm** a document
  (`needs_review` → `confirmed`).

**Real extraction** is wired as a **provider-agnostic fallback chain** (see
Extraction below): the engine walks an ordered list of `{provider, model}` steps
(e.g. Gemini → Ollama → stub), takes the first result above a confidence
threshold, and skips quota-exhausted free tiers via a circuit breaker. With no keys
configured it runs the stub only, so it behaves exactly like Slice 1.

**Auth + spaces** are implemented (Slice 3): JWT login, and shared spaces with
owner/member/viewer roles enforced on every document operation (see Auth below).

**Spend tracking** is implemented (Slice 4): spend by category / by month / summary
over confirmed documents (see Spend below).

**Reminders** are implemented (Slice 5): due/renewal/warranty reminders with a
scheduled dispatcher, and a `due` reminder auto-created when a document with a due
date is confirmed (see Reminders below).

**Anomaly detection** is implemented (Slice 6): a confirmed bill is flagged when
it's well above the trailing average for its category (see Anomalies below).

**Search** is implemented (Slice 7): natural-language and structured search over
your documents (see Search below).

**Backups + disaster recovery** are implemented (Slice 8): export/import ZIP,
rebuild-the-DB-from-sidecars, and a pg_dump job (see Backup & recovery below).

**Forward-to-file ingestion** is implemented (Slice 9): email/WhatsApp webhooks that
route a forwarded document through the normal pipeline (see Ingestion below).

**Google Drive backup** is implemented (per-owner OAuth) - see Google Drive below.

**Second-cloud mirror** (Backblaze B2, S3-compatible), **per-space ingest addresses**,
and **vital-document encryption at rest** are implemented - see below.

## Vital documents (encrypted at rest)

Flag a document `vital=true` (passport/Aadhaar/PAN/policies) and its bytes are
AES-256-GCM encrypted in object storage. Vital files are served via a decrypt-stream
endpoint (not a presigned URL); non-vital keep presigned URLs.

```bash
# upload a vital document (stored encrypted):
curl -s -F "file=@passport.jpg" -H "Authorization: Bearer $TOKEN" \
  "$B/api/documents?vital=true" | jq '.vital, .fileUrl'
# view/download (backend decrypts and streams):
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/documents/<ID>/content" -o passport.jpg
# or flag vital during review - re-encrypts the stored file in place:
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST "$B/api/documents/<ID>/confirm" -d '{"vital":true}' | jq '.vital'
```

Config: `TROVE_ENCRYPTION_KEY` (any passphrase; a 256-bit key is derived). **Back this
up out-of-band** - losing it makes vital files unrecoverable.

## Second-cloud mirror (independent copy)

Copies the whole vault (files + sidecars + dumps) to an independent S3-compatible
second cloud. **Backblaze B2** is the default target (free 10 GB, permanent, S3 API)
- the mirror reuses the same S3 code, only creds differ.

```bash
# admin: mirror now (also runs on a schedule when configured)
curl -s -H "Authorization: Bearer $TOKEN" -X POST "$B/api/admin/mirror" | jq
```

Config (env): `TROVE_MIRROR_ENABLED=true`, `TROVE_MIRROR_ENDPOINT`
(e.g. `https://s3.us-west-004.backblazeb2.com`), `TROVE_MIRROR_ACCESS_KEY`,
`TROVE_MIRROR_SECRET_KEY`, `TROVE_MIRROR_BUCKET`.

## Per-space ingest addresses

Each space can mint an unguessable ingest token → address `trove+<token>@<domain>`.
Forwarding to it files into that space (no shared secret needed); rotate to revoke.

```bash
# owner: get (creates on first call) / rotate the space's address
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/spaces/$SPID/ingest-address" | jq
curl -s -H "Authorization: Bearer $TOKEN" -X POST "$B/api/spaces/$SPID/ingest-address/rotate" | jq
# then forward with just the token (no spaceId):
curl -s -F "file=@receipt.jpg" "$B/api/ingest/email?token=<space-token>" | jq
```

## Google Drive backup (per-owner OAuth)

Each space owner connects **their own** Google Drive; Trove mirrors the space's
documents into `Trove/{category}/{yyyy-MM}/` there. Scope is `drive.file` (the app
only touches what it creates). Refresh tokens are encrypted at rest (AES-256-GCM).
Why per-owner and not a service account: a service account has **0 GB** of Drive -
per-owner OAuth uses each user's free **15 GB**, permanently. See `DECISIONS.md` D17.

Config (env): `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`,
`GOOGLE_OAUTH_REDIRECT_URI` (default `http://localhost:8080/api/integrations/google-drive/callback`),
and `TROVE_ENCRYPTION_KEY`.

```bash
# 1) Owner starts the flow - returns a 302 to Google's consent screen:
curl -s -D- -H "Authorization: Bearer $TOKEN" \
  "$B/api/integrations/google-drive/connect?spaceId=$SPID" | grep -i location
#    open that Location URL in a browser, sign in, allow.
# 2) Check status:
curl -s -H "Authorization: Bearer $TOKEN" \
  "$B/api/integrations/google-drive/status?spaceId=$SPID" | jq
# 3) Trigger a sync (owner): files appear in your Drive under Trove/…
curl -s -H "Authorization: Bearer $TOKEN" -X POST \
  "$B/api/integrations/google-drive/sync?spaceId=$SPID" | jq
```

In Google Cloud Console: the OAuth client's **Authorized redirect URI** must exactly
match `GOOGLE_OAUTH_REDIRECT_URI`, and while the app is in "Testing" your Google
account must be added as a **test user**.

## Ingestion (forward-to-file)

Public webhooks (gated by a shared secret, `trove.ingest.secret`) that file a
forwarded document into a space via the same upload→extract→review pipeline. The
document is attributed to the space owner.

```bash
# Email provider posts a forwarded attachment:
curl -s -F "file=@receipt.jpg" \
  "$B/api/ingest/email?token=$INGEST_SECRET&spaceId=$SPID&from=alice@example.com" | jq

# WhatsApp Cloud API verification handshake (GET) + inbound (POST):
curl -s "$B/api/ingest/whatsapp?hub.mode=subscribe&hub.verify_token=$INGEST_SECRET&hub.challenge=123"
curl -s -F "file=@receipt.jpg" "$B/api/ingest/whatsapp?token=$INGEST_SECRET&spaceId=$SPID" | jq
```

A missing/invalid token returns 401. Duplicate forwards are caught by content-hash
dedupe (409). For production WhatsApp, the webhook receives a media id and fetches
the bytes first, then calls the same ingestion service.

## Backup & recovery

The provider-independent safety net behind "lose the host, lose ZERO documents":

```bash
# On-demand full export (manifest.json + data.csv + files/), per space:
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/export" -o vault-export.zip

# Restore from an export ZIP (admin only): re-uploads files, rebuilds rows:
curl -s -H "Authorization: Bearer $TOKEN" -F "file=@vault-export.zip" "$B/api/import" | jq

# Disaster recovery - rebuild the whole document index from bucket sidecars (admin):
curl -s -H "Authorization: Bearer $TOKEN" -X POST "$B/api/admin/rebuild" | jq

# Database snapshot to object storage (admin; also runs on a schedule if enabled):
curl -s -H "Authorization: Bearer $TOKEN" -X POST "$B/api/admin/pg-dump" | jq

# Backup-run history:
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/admin/backup-runs" | jq
```

Admin ops are gated to the seeded dev user for now. `pg_dump` needs the binary -
set `trove.backup.pg-dump-path` (e.g. the Homebrew path) and, for the nightly run,
`trove.backup.scheduled-dump-enabled=true`. The DB is a rebuildable index: even with
an empty database, `/api/admin/rebuild` reconstructs every document from the
self-describing sidecars in object storage.

## Search

Natural-language search maps a phrase to filters (category synonyms, months/years,
"last"/"all", free text → OCR text / filename / merchant) with a rule-based parser
(no LLM, free/instant). The response echoes how the phrase was interpreted.

```bash
# natural language
curl -s -H "Authorization: Bearer $TOKEN" \
  --get "$B/api/search" --data-urlencode 'q=my last electricity bill' | jq
curl -s -H "Authorization: Bearer $TOKEN" \
  --get "$B/api/search" --data-urlencode 'q=electricity from June' | jq '.interpreted, .count'

# structured filters
curl -s -H "Authorization: Bearer $TOKEN" \
  "$B/api/search/structured?category=electricity&min=3000&from=2026-01-01&to=2026-12-31" | jq
```

## Anomalies

When a document is confirmed, its amount is compared to the trailing average of
prior **confirmed** documents in the same category. If it exceeds that average by
`trove.anomaly.threshold-pct` (default 40%) - and there's at least
`trove.anomaly.min-samples` of history - it's flagged. The verdict is stored on the
document under `extra.anomaly` and listed via:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/anomalies" | jq
# each flagged doc carries: extra.anomaly = { anomaly, average, deltaPct, sampleCount, ... }
```

Config: `trove.anomaly.threshold-pct`, `trove.anomaly.lookback-months`,
`trove.anomaly.min-samples`.

## Reminders

Types: `due`, `renewal`, `warranty_expiry`. A background scheduler scans on a fixed
interval and dispatches reminders whose date has arrived (logged for now; email/
WhatsApp channels come later). Confirming a document that has a due date
auto-creates a `due` reminder, fired `trove.reminder.lead-days` before the due date.

```bash
# list reminders in your (personal) space
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/reminders" | jq
# create a manual reminder
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST $B/api/reminders -d '{"type":"renewal","remindOn":"2026-12-01"}' | jq
# dismiss one
curl -s -H "Authorization: Bearer $TOKEN" -X POST $B/api/reminders/<ID>/dismiss | jq
```

Config: `trove.reminder.scan-fixed-delay-ms` (scan cadence) and
`trove.reminder.lead-days` (how many days before a due date to fire).

## Spend tracking

Aggregates over **confirmed** documents only (extracted amounts aren't trusted
until a human confirms them). All endpoints are authenticated and space-scoped
(default: your personal space); dates are optional ISO `yyyy-MM-dd`.

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/spend/by-category" | jq
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/spend/by-month?from=2026-01-01&to=2026-12-31" | jq
curl -s -H "Authorization: Bearer $TOKEN" "$B/api/spend/summary?spaceId=$SPID" | jq
```

## Auth (JWT) + spaces

Register/login are the only public endpoints; everything else needs
`Authorization: Bearer <token>`. Passwords are BCrypt-hashed; tokens are stateless
HS256 JWTs. A document always lives in one **space**; membership + role
(owner/member/viewer) decide who can read/write it. Registering a user also creates
their private personal space.

```bash
B=http://localhost:8080
# Log in as the seeded dev user (local only; set trove.dev.default-password)
TOKEN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dev@trove.local","password":"devpassword"}' | jq -r .token)

# or register a new account (also provisions a personal space)
curl -s -X POST $B/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","displayName":"You","password":"a-strong-password"}' | jq

# then call any endpoint with the token (defaults to your personal space):
curl -s -F "file=@receipt.jpg" -H "Authorization: Bearer $TOKEN" $B/api/documents | jq
curl -s -H "Authorization: Bearer $TOKEN" $B/api/documents | jq

# shared spaces + roles
SPID=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST $B/api/spaces -d '{"name":"Household"}' | jq -r .id)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -X POST $B/api/spaces/$SPID/members -d '{"email":"someone@example.com","role":"member"}' | jq
# upload into a specific space:
curl -s -F "file=@receipt.jpg" -H "Authorization: Bearer $TOKEN" "$B/api/documents?spaceId=$SPID" | jq
```

Config: `trove.security.jwt.secret` (set a strong value via `TROVE_JWT_SECRET` in
prod) and `trove.dev.default-password` (blank in prod to disable the dev login).

## Extraction (multi-provider, free-tier first)

Extraction is a chain, configured under `trove.extraction` in `application.yml`
(and env-overridable). Each step is a `{provider, model, effort}`; the engine
returns the first result at/above `acceptance-confidence`, and opens a per-step
circuit breaker after repeated quota errors. Which provider read a document is
recorded in that document's `extra` (`extractionProvider` / `extractionModel`).

Providers available now: `gemini` (Google Gemini free tier), `ollama` (local, free,
in-house base), and `stub` (guaranteed last resort). To enable real extraction:

```yaml
trove:
  extraction:
    acceptance-confidence: 0.55
    chain:
 - { provider: gemini, model: gemini-2.0-flash }
 - { provider: gemini, model: gemini-2.0-flash-lite }
 - { provider: ollama, model: moondream }
 - { provider: stub }
```

- **Gemini:** set `GEMINI_API_KEY` (free key from Google AI Studio).
- **Ollama:** run it locally and pull a vision model, e.g. `ollama pull moondream`;
  set `OLLAMA_ENDPOINT` if not on `localhost:11434`.

Adding another provider (Cloudflare Workers AI, Groq, etc.) is a new
`ExtractionProvider` bean + a chain entry - no other code changes.

## Layout

```
Trove/
├── DESIGN.md  DECISIONS.md                      # architecture + decision log
├── infra/docker-compose.yml                     # Postgres + MinIO for local dev
├── backend/                                     # Spring Boot (Java 21) API
│   ├── src/main/resources/db/migration/         # Flyway V1-V6 (schema + seed)
│   └── src/main/java/com/trove/                 # package-by-feature
│       ├── common/  storage/  extraction/
│       └── category/  merchant/  document/
├── web/     (placeholder - Angular, later)
└── mobile/  (placeholder - Flutter, later)
```

## Prerequisites

- **JDK 21+** (this machine has JDK 25, which runs the Java-21 build fine -
  see `DECISIONS.md` → D2). If you hit runtime proxy issues on 25, install 21:
  `sdk install java 21.0.4-tem` then `sdk use java 21.0.4-tem`.
- **Maven** (3.9+).
- **Docker + Docker Compose** (Docker Desktop). Make sure Docker is running.

## Run it locally

### 1. Start Postgres + MinIO

```bash
cd infra
docker compose up -d
# check they're healthy:
docker compose ps
```

- MinIO console: http://localhost:9001  (user `minioadmin`, pass `minioadmin`) -
  you can browse the `trove` bucket here and literally see files + sidecars appear.
- Postgres: `localhost:5432`, db/user/pass all `trove`.

### 2. Start the backend

```bash
cd ../backend
mvn spring-boot:run
# or run the built jar:
# mvn -DskipTests package && java -jar target/trove-backend-0.1.0-SNAPSHOT.jar
```

On startup Flyway creates the schema (V1-V5) and seeds one dev user, a personal
space, and the global categories (V6). The API listens on
**http://localhost:8080**.

## Try the flow (sample requests)

Use any image or PDF. The examples assume a file `receipt.jpg`.

> **Auth required:** all `/api/documents` and `/api/spaces` calls need a token now
> (see the Auth section above). Get one and export it first:
> ```bash
> TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
>   -d '{"email":"dev@trove.local","password":"devpassword"}' | jq -r .token)
> ```
> then add `-H "Authorization: Bearer $TOKEN"` to each request below.

### Upload a document

```bash
curl -s -F "file=@receipt.jpg" http://localhost:8080/api/documents | jq
```

Response (201) shows `"status": "needs_review"`. Right after upload,
`category`/`amount` may still be empty - extraction runs **asynchronously**.

### See extraction fill in (wait ~1s, then fetch it)

```bash
# grab the id from the upload response, then:
curl -s http://localhost:8080/api/documents/<ID> | jq
```

The stub extractor fills `category: "shopping"`, `merchant: "Sample Store"`,
`amount: 499.00`, `extractionConfidence: 0.5`, one line item, and
`rawText: "STUB EXTRACTION"`. Status stays `needs_review` - a human confirms.

### List documents (all, or by category)

```bash
curl -s "http://localhost:8080/api/documents" | jq
curl -s "http://localhost:8080/api/documents?category=shopping" | jq
```

### List categories

```bash
curl -s http://localhost:8080/api/categories | jq
```

### Confirm the document (optionally correcting fields)

```bash
curl -s -X POST http://localhost:8080/api/documents/<ID>/confirm \
  -H 'Content-Type: application/json' \
  -d '{"amount": 512.00, "merchant": "Reliance Fresh", "category": "food"}' | jq
```

Response now shows `"status": "confirmed"` with `reviewedBy`/`reviewedAt` set.
The sidecar in MinIO is rewritten to match.

### Duplicate detection

Upload the **same file** again → `409 Conflict` with the existing document id in
`details.existingDocumentId`.

## What proves the core principle

Open the MinIO console (http://localhost:9001) and look inside the `trove`
bucket: every file has a `.json` sidecar beside it holding its full metadata. If
Postgres were wiped, those sidecars are enough to rebuild the index - the DB is a
cache, the bucket is the truth. (The rebuild job itself is a later phase.)

## Configuration

All settings live in `backend/src/main/resources/application.yml` and are
**env-overridable**. The single source of truth for every knob - what it is, its dev
default, and the exact prod value/provider to swap in - is **[`.env.example`](.env.example)**
(richly commented). Copy it to `.env` and fill it in.

> Spring Boot does **not** read `.env` automatically. Load it before running:
> `set -a; source .env; set +a && java -jar backend/target/trove-backend-*.jar`
> (or use a systemd `EnvironmentFile`, or docker-compose `env_file`).

The extraction **chain** is a list, so it lives in `application.yml`
(`trove.extraction.chain`) or is overridden via `SPRING_APPLICATION_JSON` - see the
Extraction note in `.env.example`.

## Deploying to production (all free tier)

The app is a single stateless jar. Nothing durable lives on the host - the truth is
in object storage. Point the env vars at hosted free tiers; **no code changes**.

| Concern | Dev | Prod (free) | Env vars |
|---|---|---|---|
| Database | local Postgres | **Neon** (0.5 GB) | `TROVE_DB_URL/USER/PASSWORD` |
| Object storage | MinIO | **Cloudflare R2** (10 GB) | `TROVE_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`, `TROVE_S3_AUTO_CREATE_BUCKET=false` |
| 2nd cloud mirror | 2nd MinIO bucket | **Backblaze B2** (10 GB) | `TROVE_MIRROR_ENABLED=true` + `TROVE_MIRROR_*` |
| Human-readable backup | - | **Google Drive** (per owner, 15 GB each) | `GOOGLE_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI` |
| Extraction | stub / local Ollama | **Ollama** (self-host) or a vision API | `OLLAMA_ENDPOINT` / `GEMINI_API_KEY` + chain |
| Host | `java -jar` | **Oracle Cloud Always-Free ARM** VM | run the jar behind Caddy/Nginx for HTTPS |
| Secrets | dev defaults | **generate strong values** | `TROVE_JWT_SECRET`, `TROVE_ENCRYPTION_KEY` (`openssl rand -base64 48`) |

Steps: (1) create the Neon DB (Flyway migrates it on first boot); (2) create the R2
bucket + API token; (3) build `mvn -DskipTests package`, copy the jar to the Oracle
VM; (4) put your prod values in `/etc/trove.env` and run via systemd with
`EnvironmentFile=/etc/trove.env`; (5) front it with Caddy for automatic HTTPS and set
`GOOGLE_OAUTH_REDIRECT_URI` to the HTTPS callback (and add it to the Google OAuth
client's Authorized redirect URIs); (6) set `TROVE_DEV_PASSWORD=` (empty) to disable
the dev login. **Back up `TROVE_ENCRYPTION_KEY` out-of-band** - losing it makes vital
files unrecoverable.

## Building a client (web / mobile)

The clients aren't built yet. **[`docs/API.md`](docs/API.md)** is a self-contained
REST reference (endpoints, payloads, auth, error shape, and suggested screens) - hand
it to whoever (or whatever) builds the Angular/Flutter UI.
