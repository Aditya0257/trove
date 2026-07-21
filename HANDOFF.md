# Trove — Engineering Notes & Status

My working notes for the build: what's done, how to run it, the traps I've already hit,
and what's next. Product and architecture live in `CLAUDE.md` and `DESIGN.md`; every
non-obvious engineering decision is logged in `DECISIONS.md` (D1–D23). Kept current as
the build moves. _Last updated: 2026-07-21._

---

## 1. What Trove is

A private document vault (bills, receipts, policies, IDs, tickets). Upload a document →
it's stored in object storage with a **sidecar JSON** → an extractor (OCR + categorize +
field-extract) fills in merchant/date/amount → it lands in **`needs_review`** for me to
confirm → then spend tracking, reminders, anomaly alerts, and natural-language search
build on top. **Core principle: the app is disposable, the data is not** — three
independent copies (R2 → B2 → Google Drive), the last human-browsable without the app.
Target: 50–100 trusted users, entirely **free-tier**, optimized for reliability and zero
data loss rather than scale.

---

## 2. Stack & repo facts

- **Backend:** Spring Boot 3.3.5, Java 21 (compiled `--release 21`; dev machine runs
  JDK 25, which is fine). Package-by-feature under `com.trove`, with
  `controller/ service/ repository/ exception/` inside each feature. Flyway owns the
  schema (`V1`…`V9`); Hibernate `ddl-auto: validate`.
- **Object storage:** one `S3StorageService` (AWS S3 SDK v2) targets **MinIO** in dev
  and **Cloudflare R2** in prod by config alone (see D1).
- **Extraction:** provider-agnostic **chain** (`ExtractionEngine` walks
  `trove.extraction.chain`), circuit breaker, async via `@TransactionalEventListener
  (AFTER_COMMIT)` + reconciler. Providers: `cloudflare` (Workers AI), `gemini`,
  `ollama`, `stub`. See D3, D9, D22.
- **Search:** rule-based `NaturalQueryParser` + optional LLM `LlmQueryParser` (Ollama or
  Cloudflare text model). See D14, D22.
- **Web:** Angular 21 (standalone components, signals), runtime config from
  `web/public/config.json` (no rebuild to repoint the API). Builds to
  `web/dist/trove-web/browser`.
- **Mobile:** Flutter (single codebase, Android + iOS), under `mobile/`.
- **Repo:** `github.com/Aditya0257/trove`, commits under my personal identity.
- **Dev machine:** office laptop, no admin. Homebrew **Postgres@16** + **MinIO** +
  **Ollama** for local infra. Docker Desktop is installed and used for headless checks
  (e.g. running `flutter analyze` without a host SDK).

---

## 3. Status — what's built

- **Backend:** all build-order slices (1–9) plus the deferred features — Backblaze **B2
  mirror**, **Google Drive** per-owner OAuth backup, **vital-doc encryption at rest**
  (AES-256-GCM), **per-space ingest tokens**, **Cloudflare Workers AI** provider. Done.
- **Real cloud providers verified end-to-end** against my accounts: Neon (DB), R2
  (storage, checked by listing the bucket directly), Cloudflare Workers AI extraction +
  LLM search, B2 mirror (bucket listing matches R2 exactly), Google Drive (OAuth consent
  + sync). See D22 for the fixes that verification surfaced.
- **Notice System (D23):** the two-channel (user + developer) feedback contract, live on
  the backend, web, and mobile. Errors carry a `notice`; documents carry
  `extra.extractionMeta` (the provider chain trail + a derived notice); every response
  has an `X-Trove-Request-Id` header.
- **Web (Angular):** feature-complete, plus the Notice System — two-channel toasts, a
  grouped styled browser console, and an in-app Developer drawer.
- **Mobile (Flutter):** feature-complete client — auth, spaces, capture → upload →
  human-review confirm, document list/detail (incl. vital decrypt-stream images),
  natural-language search — all built around the notice-aware HTTP client (toasts +
  Developer drawer). `flutter analyze` → clean.

---

## 4. How to run locally

### 4a. Load `.env` literally (do NOT `source` it)
An unquoted `&` in the Neon URL makes `source` abort mid-file and silently unset every
later var. Load it the way systemd/Docker do — raw `KEY=VALUE`:
```bash
cd /Users/aditya/project-dev/Trove
set -a
while IFS= read -r l; do case "$l" in ''|\#*) ;; *=*) export "${l%%=*}=${l#*=}";; esac; done < .env
set +a
```

### 4b. Pick targets
- **Real cloud:** `.env` points at Neon + R2 + B2 + Cloudflare + Google. Add
  `export TROVE_S3_AUTO_CREATE_BUCKET=false`, and to use Cloudflare extraction:
  ```bash
  export SPRING_APPLICATION_JSON='{"trove":{"extraction":{"acceptance-confidence":0.4,"chain":[{"provider":"cloudflare"},{"provider":"stub"}]}}}'
  ```
- **Local-only:** don't load `.env`; run Homebrew Postgres@16 + MinIO (+ Ollama for real
  extraction). The `application.yml` defaults point at localhost.

### 4c. Backend
```bash
cd backend && mvn -q -DskipTests package
nohup java -jar target/trove-backend-0.1.0-SNAPSHOT.jar > /tmp/trove-app.log 2>&1 &
curl -sf http://localhost:8080/api/health   # {status:UP,app:trove}
```
Dev login (seeded by Flyway V6): `dev@trove.local` / `devpassword`; dev space
`00000000-0000-0000-0000-000000000010`. Set `TROVE_DEV_PASSWORD=` (empty) to disable in prod.

### 4d. Web
```bash
cd web && npm ci && npm start   # API base from web/public/config.json
```

### 4e. Mobile
Needs the Flutter SDK (≥ 3.27) on a machine with Android Studio/Xcode:
```bash
cd mobile && flutter create .   # generate android/ios around the tracked lib/ (one-time)
flutter pub get
flutter run --dart-define=TROVE_API_BASE=http://10.0.2.2:8080   # Android emu → host
```
Headless type/lint check via Docker (no host SDK needed) — run pub get + analyze in one
container, since the pub cache lives inside the ephemeral container:
```bash
docker run --rm -v "$PWD/mobile:/app" -w /app \
  instrumentisto/flutter:latest sh -c "flutter pub get && flutter analyze"
```

---

## 5. Gotchas (already paid for these)

- **Never `source .env`** — literal loader only (§4a); the `&` will break it silently.
- **Never quote values in `.env`** — systemd/Docker read them raw, so quotes become literal.
- **Meta Llama models need a one-time license accept** per account: `POST {"prompt":"agree"}`
  to the model once, else every call 403s with "Model Agreement". Applies to the vision
  model and, if it also 403s, the `@cf/meta/llama-3.1-8b-instruct` search model.
- **The extraction chain is a LIST** — set it via `SPRING_APPLICATION_JSON` or
  `application.yml`, not a single env var. The `application.yml` default chain is stub-only.
- **Human review is sacred** — every extraction lands `needs_review`; never auto-confirm.
- **Flyway** — never edit an applied migration (checksum mismatch); add a new `Vn`.
- **Workers AI response** — instruct models can return `result.response` as a JSON object,
  not a string; parse both. Vision models want the OpenAI-style `messages` body.

---

## 6. Security rules I hold to

- Never surface secret values anywhere — no keys, tokens, account ids, endpoints, or
  bucket names in logs/output. Outcomes, HTTP codes, and request-ids only.
- Treat any secret that leaves `.env` as compromised and rotate it. `.env` is git-ignored
  (`*.env`); only `.env.example` is committed.
- OAuth `client_id` is public by design (it rides in the browser auth URL); the
  `client_secret` never leaves the server.

---

## 7. Free-tier headroom (≤100 users, ≤20 docs/day, 6–10 concurrent)

R2 (storage), B2 (mirror), Neon (DB), Drive, the Oracle VM, and Cloudflare Pages are
comfortably free for years at this load. The only watch-item is **Cloudflare Workers AI's
~10,000 Neurons/day** free allowance, spent mainly by the 11B vision model. At ≤20
docs/day that's very likely fine, and there's no billing risk: on the free plan an
over-limit call simply fails until the next day, and the chain degrades gracefully
(extraction → stub → `needs_review`; search → rule-based parser). Levers if it's ever
tight: a lighter vision model, or downscale images before sending.

---

## 8. Roadmap / next

- ✅ Web client + production readiness
- ✅ Real cloud providers verified end-to-end
- ✅ Notice System (backend + web + mobile)
- ✅ Flutter mobile client (analyze-clean)
- ✅ Reminders: multi-lead (7/1/0 days before due) + email delivery via Brevo (free,
  swappable `EmailSender`, safe no-op until configured) + on-device phone notifications
  on mobile (`flutter_local_notifications`, free). Warranty = a document whose due date
  is its expiry, so the same path covers it. Config: `TROVE_EMAIL_*`,
  `TROVE_REMINDER_LEAD_DAYS_LIST`.
- ⏭️ **Deploy:** Oracle Always-Free VM + DuckDNS (`trove-sync.duckdns`) + Caddy (HTTPS) +
  Cloudflare Pages (web). Google OAuth redirect keeps `localhost` and adds the prod HTTPS
  callback; Drive owners reconnect once against the prod redirect URI. See `docs/DEPLOYMENT.md`.
- ⏭️ CI/CD auto-deploy (`.github/workflows/ci.yml` already builds backend + web).
- ⏭️ Optional polish: a live daily-Neuron usage gauge (needs a Cloudflare analytics
  poll) + per-request token counts in `extractionMeta`; a `docs/API.md` section
  describing the notice envelope.
- 🧹 Housekeeping: 8 test documents are sitting in my real Neon/R2/B2/Drive from the
  verification pass — clear them before real use (empty the R2 bucket, then
  `POST /api/admin/rebuild`).

---

## 9. API quick reference

- Auth: `POST /api/auth/login|register {email,password[,displayName]}` →
  `{token,userId,email,displayName}`.
- Spaces: `GET /api/spaces` → `[{id,name,kind,createdBy,createdAt}]`.
- Documents: `GET /api/documents?spaceId=&category=`; `POST /api/documents` (multipart
  `file`, params `spaceId`, `vital`); `GET /api/documents/{id}`;
  `GET /api/documents/{id}/content` (bytes — used for vital docs);
  `POST /api/documents/{id}/confirm` body
  `{category,merchant,docDate,amount,currency,dueDate}`.
- Search: `GET /api/search?q=&spaceId=` → `{interpreted, count, results:[document]}`.
- Categories: `GET /api/categories` → `[{code,label,global}]`.
- DocumentResponse: `id, spaceId, category(code), merchant, docDate, amount, currency,
  dueDate, rawText, extra{extractionProvider, extractionMeta{…, notice{…}}},
  extractionConfidence, vital, encrypted, status(needs_review|confirmed), fileUrl,
  lineItems`.
