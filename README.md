# Trove

A private vault for the documents that matter — bills, receipts, policies,
warranties, IDs. You upload a document; Trove stores it durably, reads it, files
it by category, and lets you review and confirm the extracted fields.

> **Read first:** [`DESIGN.md`](DESIGN.md) (architecture, schema, interfaces) and
> [`DECISIONS.md`](DECISIONS.md) (the running log of build decisions and their
> reasoning).

## Where we are: **Slice 1** (the first end-to-end vertical)

Implemented, and nothing beyond it yet:

- **Upload** a file → hash it → reject duplicates in the space → store it in
  object storage **with a sidecar JSON** → insert a `needs_review` row.
- **Extraction** runs **asynchronously** (stub provider for now) and fills in
  category / merchant / date / amount, then rewrites the sidecar.
- **List** documents (optionally by category) and **confirm** a document
  (`needs_review` → `confirmed`).

**Real extraction** is wired as a **provider-agnostic fallback chain** (see
Extraction below): the engine walks an ordered list of `{provider, model}` steps
(e.g. Gemini → Ollama → stub), takes the first result above a confidence
threshold, and skips quota-exhausted free tiers via a circuit breaker. With no keys
configured it runs the stub only, so it behaves exactly like Slice 1.

**Auth + spaces** are implemented (Slice 3): JWT login, and shared spaces with
owner/member/viewer roles enforced on every document operation (see Auth below).

Later phases (spend, reminders, anomalies, search, backups, ingestion) are **not**
built yet — see `DESIGN.md` §5.

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
`ExtractionProvider` bean + a chain entry — no other code changes.

## Layout

```
Trove/
├── DESIGN.md  DECISIONS.md                      # architecture + decision log
├── infra/docker-compose.yml                     # Postgres + MinIO for local dev
├── backend/                                     # Spring Boot (Java 21) API
│   ├── src/main/resources/db/migration/         # Flyway V1–V6 (schema + seed)
│   └── src/main/java/com/trove/                 # package-by-feature
│       ├── common/  storage/  extraction/
│       └── category/  merchant/  document/
├── web/     (placeholder — Angular, later)
└── mobile/  (placeholder — Flutter, later)
```

## Prerequisites

- **JDK 21+** (this machine has JDK 25, which runs the Java-21 build fine —
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

- MinIO console: http://localhost:9001  (user `minioadmin`, pass `minioadmin`) —
  you can browse the `trove` bucket here and literally see files + sidecars appear.
- Postgres: `localhost:5432`, db/user/pass all `trove`.

### 2. Start the backend

```bash
cd ../backend
mvn spring-boot:run
# or run the built jar:
# mvn -DskipTests package && java -jar target/trove-backend-0.1.0-SNAPSHOT.jar
```

On startup Flyway creates the schema (V1–V5) and seeds one dev user, a personal
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
`category`/`amount` may still be empty — extraction runs **asynchronously**.

### See extraction fill in (wait ~1s, then fetch it)

```bash
# grab the id from the upload response, then:
curl -s http://localhost:8080/api/documents/<ID> | jq
```

The stub extractor fills `category: "shopping"`, `merchant: "Sample Store"`,
`amount: 499.00`, `extractionConfidence: 0.5`, one line item, and
`rawText: "STUB EXTRACTION"`. Status stays `needs_review` — a human confirms.

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
Postgres were wiped, those sidecars are enough to rebuild the index — the DB is a
cache, the bucket is the truth. (The rebuild job itself is a later phase.)

## Configuration

All settings live in `backend/src/main/resources/application.yml` and are
env-overridable. To point at real Cloudflare R2 + Neon in prod, set
`TROVE_S3_ENDPOINT`, `TROVE_S3_ACCESS_KEY`, `TROVE_S3_SECRET_KEY`,
`TROVE_S3_BUCKET`, and `TROVE_DB_URL`/`TROVE_DB_USER`/`TROVE_DB_PASSWORD` — no
code change (the storage impl speaks S3 to both; see `DECISIONS.md` → D1).
