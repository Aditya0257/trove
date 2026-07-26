# Trove - Backend API reference (frontend handoff)

This is a self-contained spec for building the **web (Angular)** and **mobile
(Flutter)** clients against the Trove backend. It reflects the implemented API.
Base URL in dev: `http://localhost:8080`.

## Conventions

- **Auth:** every endpoint except `/api/auth/**`, `/api/ingest/**`, and the Google
  callback requires header `Authorization: Bearer <JWT>`.
- **Content type:** JSON in/out, except uploads (`multipart/form-data`, field `file`)
  and file downloads.
- **Spaces:** most endpoints take an optional `spaceId` query param; if omitted they
  default to the caller's **personal space**. A document belongs to one space; access
  is by membership + role (`owner` > `member` > `viewer`; viewers are read-only).
- **Review lifecycle:** uploaded/extracted docs are `needs_review` until a human
  `confirm`s them (`confirmed`). Extraction is async - after upload, poll the document
  until `extractionConfidence` is non-null.
- **Errors:** uniform JSON body
  `{ "timestamp", "status", "error", "message", "path", "details" }`. Codes: 400
  bad request, 401 unauthenticated/bad token, 403 not a member / insufficient role,
  404 not found, 409 conflict (duplicate upload / email taken).

## Auth (public)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/api/auth/register` | `{email, displayName, password}` (password ≥ 8) | `202 {status:"unverified"}`, no token; emails a 6-digit code (also creates a personal space). Client then calls verify-email. |
| POST | `/api/auth/verify-email` | `{email, code}` | Confirms the code, then applies the approval gate: `{token, ...}` (open registration / admin) or `{status:"pending"}`. |
| POST | `/api/auth/resend-verification` | `{email}` | `204` always (anti-enumeration). |
| POST | `/api/auth/login` | `{email, password, code?}` | `{token, userId, email, displayName}`; or `{twoFactorRequired:true}` (resubmit with `code`); or a non-active `status` (`unverified`/`pending`/`rejected`) with no token. |
| POST | `/api/auth/forgot-password` | `{email}` | `204` always (anti-enumeration). |
| POST | `/api/auth/reset-password` | `{token, newPassword}` | `204`; completes reset with the emailed token. |

Store the `token`; send it as `Authorization: Bearer <token>` on every other call.

## Account (authenticated)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/account/me` | - | `{email, displayName, admin, twoFactorEnabled, avatarUrl, pendingEmail, createdAt}` |
| POST | `/api/account/profile` | `{displayName}` | update display name |
| POST | `/api/account/password` | `{currentPassword, newPassword}` | re-checks the current password |
| POST | `/api/account/email` | `{newEmail, password}` | start email change; OTP sent to the **new** address |
| POST | `/api/account/email/verify` | `{code}` | confirm the new email (swaps the live email) |
| POST | `/api/account/photo` | multipart `file` | store avatar in R2 → `{avatarUrl}` (presigned) |
| DELETE | `/api/account/photo` | - | remove the avatar |
| GET | `/api/account/2fa/status` | - | whether TOTP is enabled |
| POST | `/api/account/2fa/setup` | - | begin setup → secret + `otpauth://` URI |
| POST | `/api/account/2fa/enable` | `{code}` | enable after proving a code |
| POST | `/api/account/2fa/disable` | `{code}` | disable |

## Documents

| Method | Path | Notes |
|---|---|---|
| POST | `/api/documents?spaceId=&vital=false` | multipart `file`. `vital=true` → encrypted at rest. `201 DocumentResponse` (status `needs_review`) |
| GET | `/api/documents?spaceId=&category=&page=&size=` | list (newest first), optional category code filter. Returns one page as the array body; the total match count is in the `X-Total-Count` response header (CORS-exposed). `size=0` or omitted returns all. With no category the `email` category is excluded (emails live under Mail). |
| GET | `/api/documents/mail-bundle?spaceId=&bundleId=` | the emails in one mail thread, oldest first |
| GET | `/api/documents/{id}` | one document |
| GET | `/api/documents/{id}/content` | raw file bytes (decrypted if vital) - use this to render/download |
| GET | `/api/documents/{id}/file` | `{ "url" }` - presigned URL (non-vital) or the `/content` path (vital) |
| POST | `/api/documents/{id}/confirm` | body optional: `{category?, merchant?, docDate?, amount?, currency?, dueDate?, vital?, extra?}` → `confirmed` |
| POST | `/api/documents/{id}/reextract` | re-run AI reading (after a read that timed out and left it blank); resets confidence + re-dispatches extraction. Vital/encrypted docs are rejected. |
| GET | `/api/documents/{id}/related` | other confirmed docs from the same merchant (else same category), newest first - the auto-link view. `[DocumentResponse]` |

**DocumentResponse**:
```json
{
  "id","spaceId","uploadedBy","storageKey","sidecarKey","fileHash","mimeType",
  "sizeBytes","originalFilename","category","merchant","docDate","amount","currency",
  "dueDate","rawText","extra","extractionConfidence","vital","status","reviewedBy",
  "reviewedAt","createdAt","updatedAt","fileUrl",
  "lineItems":[{"description","quantity","unitPrice","amount"}]
}
```
`fileUrl` is a short-lived presigned URL for non-vital docs, or `/api/documents/{id}/content`
for vital (encrypted) docs. `extra.anomaly` is present when flagged (see Anomalies).
`extra.extractionProvider` shows which provider read it.

## Mail

| Method | Path | Returns |
|---|---|---|
| GET | `/api/mail?spaceId=&page=&size=` | one page of email **threads** (bundles) grouped server-side, plus autocomplete facets: `{bundles, total, accounts, topics, addresses}`. The total thread count is also in the `X-Total-Count` header; `size=0` returns all. |

Open a thread with `GET /api/documents/mail-bundle?spaceId=&bundleId=` (the emails in that thread, oldest first).

## Categories

| Method | Path | Returns |
|---|---|---|
| GET | `/api/categories?spaceId=` | `[{code, label, global}]` |

## Spaces & members

| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/api/spaces` | `{name}` | create shared space (caller = owner) |
| GET | `/api/spaces` | - | spaces the caller belongs to |
| GET | `/api/spaces/{id}/members` | - | roster (any member) |
| POST | `/api/spaces/{id}/members` | `{email, role}` | add/re-role (owner only); role ∈ owner\|member\|viewer |

## Spend tracking

| Method | Path | Returns |
|---|---|---|
| GET | `/api/spend/by-category?spaceId=&from=&to=` | `[{category, label, total, count}]` |
| GET | `/api/spend/by-month?spaceId=&from=&to=` | `[{period, total, count}]` (period `YYYY-MM`) |
| GET | `/api/spend/summary?spaceId=&from=&to=` | `{from, to, total, count, byCategory[]}` |

Dates are ISO `yyyy-MM-dd`, optional. Only **confirmed** documents are counted.

## Reminders

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/reminders?spaceId=&status=` | - | status ∈ pending\|sent\|dismissed. Each row includes `documentFilename` (the linked document's file name, resolved in one batch query) so you needn't fetch the document list. |
| POST | `/api/reminders?spaceId=` | `{documentId?, type, remindOn}` | type ∈ due\|renewal\|warranty_expiry |
| POST | `/api/reminders/{id}/dismiss` | - | mark dismissed |

A `due` reminder is auto-created when a document with a due date is confirmed.

## Document intelligence (Insights)

| Method | Path | Returns |
|---|---|---|
| GET | `/api/insights/expiring?spaceId=&withinDays=90` | `[{documentId, title, category, kind, date, daysLeft, amount, currency}]` - bills due, renewals and warranty ends within the window (plus anything lapsed in the last 30 days), soonest first. `kind` ∈ due\|renewal\|warranty; `daysLeft` is signed (negative = overdue). Items already marked Done or Dismissed in Reminders are excluded. |
| GET | `/api/insights/recurring?spaceId=` | `[{merchant, category, categoryLabel, occurrences, cadence, averageAmount, currency, lastSeen, nextExpected}]` - merchant+category groups that repeat on a regular cadence (`weekly\|monthly\|quarterly\|yearly`), with the predicted next date. |

Read-only, computed live from **confirmed** documents (no extra storage, no AI cost). Reminders stays the action inbox that notifies you; Insights is the read-only overview of what is still outstanding, plus recurring/subscription detection. Related documents are served by `GET /api/documents/{id}/related` (see Documents).

## Anomalies

| Method | Path | Returns |
|---|---|---|
| GET | `/api/anomalies?spaceId=` | `[DocumentResponse]` flagged as "higher than usual" |

Each flagged doc carries `extra.anomaly = { anomaly, amount, average, deltaPct, sampleCount, thresholdPct, enoughHistory }`.

## Search

| Method | Path | Returns |
|---|---|---|
| GET | `/api/search?q=&spaceId=` | `{interpreted: SearchQuery, count, results: [DocumentResponse]}` - natural language ("my last water bill") |
| GET | `/api/search/structured?spaceId=&category=&text=&status=&from=&to=&min=&max=&limit=` | `[DocumentResponse]` |

`interpreted` echoes how the phrase was parsed (category, date range, text, latestOnly, limit) - show it as "showing: …".

## Admin - accounts (admin only)

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/api/admin/users` | - | all accounts |
| GET | `/api/admin/pending` | - | accounts awaiting approval |
| POST | `/api/admin/users/{id}/approve` | - | approve (status → active) |
| POST | `/api/admin/users/{id}/reject` | - | reject |
| POST | `/api/admin/users/{id}/delete` | `{confirmEmail}` | irreversible account + data delete; `confirmEmail` must match the target. Cannot delete yourself or another admin. |

## Backup / admin (admin = configured admin allow-list)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/export?spaceId=` | downloads `vault-export-<date>.zip` (manifest.json + data.csv + files/) |
| POST | `/api/import` | multipart `file` (a ZIP) - restore. **admin** |
| POST | `/api/admin/rebuild` | rebuild DB index from object-storage sidecars. **admin** |
| POST | `/api/admin/pg-dump` | `{key}` - DB snapshot to storage. **admin** |
| POST | `/api/admin/mirror` | `{copied, skipped}` - copy to 2nd cloud. **admin** |
| GET | `/api/admin/backup-runs` | `[{kind,status,location,startedAt,finishedAt,detail}]`. **admin** |

## Ingestion (public webhooks, secret-gated)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/spaces/{spaceId}/ingest-address` | `{token, address}` - the space's forward-to-file address (owner) |
| POST | `/api/spaces/{spaceId}/ingest-address/rotate` | new token (owner) |
| POST | `/api/ingest/email?token=&spaceId=&from=` | multipart `file`; `token` = per-space token (spaceId optional) or shared secret (+spaceId). `202` |
| GET | `/api/ingest/whatsapp?hub.mode=&hub.verify_token=&hub.challenge=` | Meta verification handshake |
| POST | `/api/ingest/whatsapp?token=&spaceId=&from=` | multipart `file`. `202` |

## Google Drive backup (per space owner)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/integrations/google-drive/connect?spaceId=` | owner → `302` to Google consent |
| GET | `/api/integrations/google-drive/callback?code=&state=` | public (Google redirects here) |
| GET | `/api/integrations/google-drive/status?spaceId=` | `{connected, connectedAt, lastSyncAt}` |
| POST | `/api/integrations/google-drive/sync?spaceId=` | owner → `{synced, skipped}` |

## Suggested client screens (maps to the API)

1. **Auth** - register / login (store JWT).
2. **Capture/Upload** - pick or snap a file → `POST /api/documents` (offer a "vital"
   toggle); show it as `needs_review`, poll `GET /api/documents/{id}` until extracted.
3. **Review & confirm** - form pre-filled from extraction (category, merchant, amount,
   dates); highlight low `extractionConfidence`; `POST …/confirm`.
4. **Browse** - list by category; document detail renders via `/content`.
5. **Spend dashboard** - `/api/spend/*` charts.
6. **Reminders** - list + dismiss.
7. **Insights** - `/api/insights/expiring` (with a day-window) + `/api/insights/recurring`; a
   "related documents" panel on Review via `/api/documents/{id}/related`.
8. **Search** - one box → `/api/search?q=…`, show `interpreted`.
9. **Spaces** - create/join, manage members, show the space's ingest address.
9. **Settings/backup** - connect Google Drive (owner), export ZIP, connection status.
