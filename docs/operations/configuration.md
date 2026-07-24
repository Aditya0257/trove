# Configuration Reference

Every environment variable and configuration property Trove reads, grouped by concern, with its
default and its dev-versus-production guidance. All configuration is bound under `trove.*` in
`application.yml` and supplied by environment variables; secrets are never committed. Deployment
steps are in [../DEPLOYMENT.md](../DEPLOYMENT.md).

Rule of thumb: the defaults are wired for local development (MinIO, local Postgres, dev secrets)
so the stack runs with no cloud account. Production overrides every credential and secret and
points storage and database at the real providers.

## Database

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_DB_URL` | `jdbc:postgresql://localhost:5432/trove` | Production points at Neon; the URL requires `?sslmode=require`. |
| `TROVE_DB_USER` / `TROVE_DB_PASSWORD` | dev values | Neon credentials in production. |

Flyway owns the schema and runs migrations on start-up; Hibernate is `ddl-auto: validate`.

## Object storage (Tier 1, R2 / MinIO)

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_S3_ENDPOINT` | `http://localhost:9000` | MinIO locally; the R2 endpoint in production. |
| `TROVE_S3_REGION` | `us-east-1` | R2 uses `auto`. |
| `TROVE_S3_ACCESS_KEY` / `TROVE_S3_SECRET_KEY` | `minioadmin` | R2 API token in production. |
| `TROVE_S3_BUCKET` | `trove` | |
| `TROVE_S3_AUTO_CREATE_BUCKET` | `true` | Handy in dev; harmless in prod if the token lacks CreateBucket, set false. |
| `TROVE_S3_PRESIGN_TTL` | `900` | Lifetime in seconds of presigned view URLs (15 minutes). |

Uploads are capped by Spring's multipart limits: `max-file-size` 25 MB, `max-request-size` 30 MB.

## Mirror (Tier 2, Backblaze B2)

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_MIRROR_ENABLED` | `false` | Turn on in production to run the hourly key-diff mirror. |
| `TROVE_MIRROR_ENDPOINT` / `_REGION` / `_ACCESS_KEY` / `_SECRET_KEY` / `_BUCKET` | empty | Backblaze B2 (S3-compatible) credentials and bucket. |

## Google Drive (Tier 3)

| Variable | Default | Notes |
| --- | --- | --- |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | empty | OAuth client for the `drive.file` scope. |
| `TROVE_WEB_BASE_URL` | `http://localhost:4200` | Used to build the OAuth redirect back to the app. |
| `TROVE_DRIVE_SCHEDULED_SYNC` | `true` | Whether the hourly Drive sync job runs. |

## AI (Cloudflare Workers AI) and the budget

| Variable | Default | Notes |
| --- | --- | --- |
| `CF_ACCOUNT_ID` / `CF_API_TOKEN` | empty | Workers AI credentials (vision, embeddings, chat). |
| `CF_MODEL` | `@cf/meta/llama-3.2-11b-vision-instruct` | The extraction vision model. |
| `TROVE_AI_DAILY_NEURON_LIMIT` | `10000` | Shared daily budget across the whole app. |
| `TROVE_AI_PER_USER_NEURON_LIMIT` | `2000` | Per-user cap so one user cannot exhaust the pool. |
| `TROVE_EXTRACTION_MAX_POOL` | `4` | Extraction worker thread pool size. |
| `TROVE_EXTRACTION_BREAKER_FAILS` | `2` | Failures before the provider circuit breaker trips to the fallback. |

The extraction provider chain (`trove.extraction.providers.*`) and the chat models
(`trove.chat.*`, embedding and router and standard models, top-k, max-distance,
budget-downgrade fraction) are configurable but default sensibly; the fallback providers Gemini
and Ollama are configurable alternatives to Cloudflare. See
[../architecture/05-ai-and-extraction.md](../architecture/05-ai-and-extraction.md).

## Reminders and anomaly

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_REMINDER_SCAN_DELAY_MS` | `3600000` | Dispatch scan interval (1 hour). |
| `TROVE_REMINDER_LEAD_DAYS_LIST` | `7,1,0` | Days before a due date to fire reminders. |
| warranty lead days | `14,0` | Days before a warranty expiry to fire (config `trove.reminder.warranty-lead-days-list`). |
| `TROVE_ANOMALY_THRESHOLD` | `0.40` | Flag when an amount is this fraction over the category average. |
| anomaly lookback / min-samples | `12` months / `3` | Config `trove.anomaly.lookback-months` and `min-samples`. |

## Security and secrets

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_JWT_SECRET` | dev-only placeholder | HMAC-SHA256 signing key. Must be a strong random value in production (32+ bytes). |
| `TROVE_JWT_EXPIRATION_MINUTES` | `720` | Token lifetime (12 hours). |
| `TROVE_ENCRYPTION_KEY` | dev-only placeholder | AES-256-GCM key for vital documents and stored secrets. Must be strong in production. |
| `TROVE_ADMIN_EMAIL` | unset | The single admin account (approves sign-ups, runs backup/rebuild). Set to a real email in production, not a `.local` address. |

## Email (Brevo) and ingestion

| Variable | Default | Notes |
| --- | --- | --- |
| `TROVE_EMAIL_API_KEY` | empty | Brevo key for reset and reminder email. |
| `TROVE_EMAIL_FROM` / `TROVE_EMAIL_FROM_NAME` | empty / `Trove` | Sender identity. |
| `TROVE_INGEST_ENABLED` | `true` | Whether the forward-to-file webhooks are active. |
| `TROVE_INGEST_SECRET` | `dev-ingest-secret` | Shared secret the mail provider must present. Set a strong value in production. |

## Production checklist

Before going live, ensure these are set to real, strong values (not the dev defaults):
`TROVE_JWT_SECRET`, `TROVE_ENCRYPTION_KEY`, `TROVE_INGEST_SECRET`, `TROVE_ADMIN_EMAIL`, the Neon
database URL and credentials, the R2 credentials and bucket, and (for full three-tier durability)
the B2 mirror and Google OAuth credentials. The dev seed user (Flyway V6) should be gated out of
production. See [../DEPLOYMENT.md](../DEPLOYMENT.md).
