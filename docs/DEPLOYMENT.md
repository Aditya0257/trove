# Trove - Deployment (all free tier)

Deploy the **API** (stateless jar/container) + the **web** (static Angular) onto
permanently-free services. No code changes - only env vars (see `.env.example`) and
one web constant. Iterate locally, `git push`, redeploy.

## The pieces (all free, no card except the Oracle VM signup)

| Piece | Free service | Notes |
|---|---|---|
| Database | **Neon** | Postgres, 0.5 GB |
| Object storage (truth) | **Cloudflare R2** | 10 GB, zero egress |
| 2nd-cloud mirror | **Backblaze B2** | 10 GB, S3-compatible |
| Human backup | **Google Drive** | per-owner OAuth, 15 GB each |
| Extraction + NL search | **Cloudflare Workers AI** | free daily allowance, hosted |
| API host | **Oracle Cloud Always-Free ARM VM** | runs the jar (or Docker) |
| Web host | **Cloudflare Pages** | static Angular, free |

## 1. Fill `.env`
`cp .env.example .env` and set every value (Neon URL, R2 keys, B2 keys, Google OAuth,
`CF_ACCOUNT_ID`/`CF_API_TOKEN`, and strong `TROVE_JWT_SECRET` + `TROVE_ENCRYPTION_KEY`
via `openssl rand -base64 48`). Set `TROVE_DEV_PASSWORD=` (empty) to disable the dev
login. Enable hosted AI:
```
TROVE_MIRROR_ENABLED=true
TROVE_SEARCH_LLM_ENABLED=true
TROVE_SEARCH_LLM_PROVIDER=cloudflare
TROVE_SEARCH_LLM_MODEL=@cf/meta/llama-3.1-8b-instruct
SPRING_APPLICATION_JSON={"trove":{"extraction":{"acceptance-confidence":0.4,"chain":[{"provider":"cloudflare"},{"provider":"stub"}]}}}
```

## 2. Provision the free services
- **Neon**: create project → copy connection string → `TROVE_DB_URL/USER/PASSWORD`.
  Flyway migrates the schema on first boot.
- **Cloudflare R2**: create bucket `trove` + an R2 API token → `TROVE_S3_*`
  (`TROVE_S3_AUTO_CREATE_BUCKET=false`).
- **Cloudflare Workers AI**: an API token with Workers AI permission → `CF_*`. The
  default vision model (`@cf/meta/llama-3.2-11b-vision-instruct`) and the search text
  model (`@cf/meta/llama-3.1-8b-instruct`) are **Meta Llama models that require a
  one-time license acceptance per account** before first use - otherwise every call
  returns HTTP 403 "Model Agreement". Accept once with:
  ```bash
  curl -s -X POST "https://api.cloudflare.com/client/v4/accounts/$CF_ACCOUNT_ID/ai/run/@cf/meta/llama-3.2-11b-vision-instruct" \
    -H "Authorization: Bearer $CF_API_TOKEN" -H 'Content-Type: application/json' -d '{"prompt":"agree"}'
  # if the search model also 403s with "Model Agreement", repeat for @cf/meta/llama-3.1-8b-instruct
  ```
  These models are free within the daily Neuron allowance; over-limit calls just fall
  back to the stub (extraction) or rule-based parser (search) - you are never charged.
- **Backblaze B2**: bucket + Application Key → `TROVE_MIRROR_*`.
- **Google**: add the HTTPS redirect URI to your OAuth client → `GOOGLE_OAUTH_*`.

## 3. Run the API on the Oracle VM

**Option A - plain jar + systemd (simplest):**
```bash
# on your machine
cd backend && mvn -DskipTests package
scp target/trove-backend-0.1.0-SNAPSHOT.jar ubuntu@<vm>:/opt/trove/app.jar
scp .env ubuntu@<vm>:/tmp/trove.env
# on the VM
sudo apt install -y openjdk-21-jre-headless postgresql-client
sudo mv /tmp/trove.env /etc/trove.env && sudo chmod 600 /etc/trove.env
sudo cp infra/deploy/trove.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now trove
journalctl -u trove -f
```

**Option B - Docker (portable):**
```bash
# on the VM (Docker installed)
docker build -t trove-api backend/
docker run -d --env-file /etc/trove.env -p 8080:8080 --restart unless-stopped trove-api
```

## 4. HTTPS
Point `api.yourdomain.com` DNS at the VM, edit `infra/deploy/Caddyfile` (domain), and
run Caddy - it gets + renews a Let's Encrypt cert and proxies to `localhost:8080`.
(A free hostname works too, e.g. a DuckDNS subdomain.)

## 5. Web on Cloudflare Pages
The API URL is read at runtime from `web/public/config.json` (no rebuild to
repoint). Set it to your API:
```json
{ "apiBase": "https://api.yourdomain.com" }
```
Then connect the repo in the Cloudflare Pages dashboard (build command `ng build`,
output dir `web/dist/trove-web/browser`, root `web`) or upload that folder - the
`config.json` ships at the site root and is fetched on load. Pages serves it free
over HTTPS.

## 6. Verify
Open the Pages URL → register → upload → confirm; check spend/search; connect Drive.
`GET https://api.yourdomain.com/api/admin/backup-runs` (as the admin user) shows the
scheduled mirror/dump/drive-sync runs. Losing the DB? `POST /api/admin/rebuild`
reconstructs it from the R2 sidecars.

## Iterating after deploy
Change code locally → test on `localhost` → `git push` → on the VM `git pull` +
rebuild/redeploy (Option A: rebuild jar, `scp`, `systemctl restart trove`; Option B:
`docker build` + re-run). Cloudflare Pages auto-rebuilds the web on push if you
connected the repo.
