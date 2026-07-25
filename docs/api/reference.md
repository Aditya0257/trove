# API Reference

Every REST endpoint Trove exposes, grouped by resource. For the concepts behind the auth
header, the space-scoping query parameter, and the error envelope, see
[../architecture/00-concepts.md](../architecture/00-concepts.md) and
[../architecture/03-security-and-access.md](../architecture/03-security-and-access.md).

## Conventions

- **Base path:** all routes are under `/api`.
- **Authentication:** send `Authorization: Bearer <jwt>` on every request except the public
  routes listed below. The token comes from `POST /api/auth/login`.
- **Space scoping:** most reads and writes accept an optional `spaceId` query parameter. When
  omitted, the caller's personal space is used. The caller must be a member of the target space;
  the required role (read or write) is noted per endpoint and enforced as described in the
  security document.
- **Errors:** failures return a Notice envelope, never a stack trace: a JSON body with a
  user-facing `message`, a developer `detail`, and a stable `code`, plus the appropriate HTTP
  status (400 validation, 401 unauthenticated, 403 forbidden, 404 not found, 409 conflict, 413
  too large, 5xx unexpected).
- **Public routes (no token):** `POST /api/auth/**`, `GET /api/health`, `POST /api/ingest/**`,
  and `GET /api/integrations/google-drive/callback`.
- **Admin routes:** the endpoints marked "admin" check the caller's email against the configured
  admin allow-list (`trove.admin.emails`, merged with the legacy single `trove.admin.email`).

## Auth and account

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| POST | `/api/auth/register` | Create an account. Always starts `unverified` with no token and emails a 6-digit code; the client then calls verify-email. Body: `{email, displayName, password}`. | public |
| POST | `/api/auth/verify-email` | Confirm the emailed code. On success applies the approval gate: returns a token (open registration/admin) or `status:"pending"`. Body: `{email, code}`. | public |
| POST | `/api/auth/resend-verification` | Resend the verification code. Always 204 (anti-enumeration). Body: `{email}`. | public |
| POST | `/api/auth/login` | Verify credentials. Returns a token; or `{twoFactorRequired:true}` (resubmit with `code`); or a non-active `status` (`unverified`, `pending`, `rejected`) with no token. Body: `{email, password, code?}`. | public |
| POST | `/api/auth/forgot-password` | Begin password reset. Always 204 (anti-enumeration). Body: `{email}`. | public |
| POST | `/api/auth/reset-password` | Complete reset with the emailed token. Body: `{token, newPassword}`. | public |
| GET | `/api/account/me` | The caller's profile: `{email, displayName, admin, twoFactorEnabled, avatarUrl, pendingEmail, createdAt}`. | authenticated |
| POST | `/api/account/profile` | Update the display name. Body: `{displayName}`. | authenticated |
| POST | `/api/account/password` | Change password; re-checks the current one. Body: `{currentPassword, newPassword}`. | authenticated |
| POST | `/api/account/email` | Start an email change; emails an OTP to the new address. Body: `{newEmail, password}`. | authenticated |
| POST | `/api/account/email/verify` | Confirm the new email with the code, swapping the live email. Body: `{code}`. | authenticated |
| POST | `/api/account/photo` | Upload a profile photo (multipart `file`), stored in R2. Returns `{avatarUrl}` (presigned). | authenticated |
| DELETE | `/api/account/photo` | Remove the profile photo. | authenticated |
| GET | `/api/account/2fa/status` | Whether TOTP is enabled for the caller. | authenticated |
| POST | `/api/account/2fa/setup` | Begin TOTP setup; returns the secret and `otpauth://` URI. | authenticated |
| POST | `/api/account/2fa/enable` | Enable TOTP after proving a code. Body: `{code}`. | authenticated |
| POST | `/api/account/2fa/disable` | Disable TOTP. Body: `{code}`. | authenticated |

## Admin

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/admin/users` | List all accounts. | admin |
| GET | `/api/admin/pending` | List accounts awaiting approval. | admin |
| POST | `/api/admin/users/{id}/approve` | Approve a pending account (status becomes active). | admin |
| POST | `/api/admin/users/{id}/reject` | Reject a pending account. | admin |
| POST | `/api/admin/users/{id}/delete` | Irreversibly delete an account and all its data (live storage, Drive and the index). Body: `{confirmEmail}` must match the target's email. Cannot delete your own account or another admin. | admin |

## Documents

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| POST | `/api/documents` | Upload a file (multipart). Query: `spaceId?`, `vital?`, `extract?`. Rejects non image/PDF (400) and oversize (413). Returns the created document in `needs_review`. | write |
| GET | `/api/documents` | List live documents. Query: `spaceId?`, `category?`, `page?`, `size?`. Returns one page as the array body; the total match count is in the `X-Total-Count` response header (CORS-exposed). `size=0` or omitted returns all (back-compatible). With no category the `email` category is excluded (emails live under Mail). | read |
| GET | `/api/documents/mail-bundle` | The emails in one mail thread, oldest first. Query: `spaceId?`, `bundleId`. | read |
| GET | `/api/documents/{id}` | Get one document (fields, extraction trail, presigned file URL). | read |
| GET | `/api/documents/{id}/file` | Redirect or presigned URL to the original file (non-vital). | read |
| GET | `/api/documents/{id}/content` | Stream the file bytes, decrypting vital documents in transit. | read |
| POST | `/api/documents/{id}/confirm` | Apply reviewer edits and mark confirmed. Body: `{category?, merchant?, docDate?, amount?, currency?, dueDate?, vital?, extra?}`. Fires reminders, indexing and the anomaly check. | write |
| DELETE | `/api/documents/{id}` | Soft delete (moves to Trash, recoverable 30 days). | write |
| POST | `/api/documents/{id}/restore` | Restore a trashed document. | write |
| DELETE | `/api/documents/{id}/purge` | Permanently remove from live storage and the database (B2 archive retains a copy). | write |
| GET | `/api/documents/trash` | List trashed documents. Query: `spaceId?`. | read |

## Mail

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/mail` | List email threads (bundles), grouped server-side. Query: `spaceId?`, `page?`, `size?`. Returns one page of bundles plus autocomplete facets `{bundles, total, accounts, topics, addresses}`; the total thread count is also in the `X-Total-Count` response header. `size=0` returns all. | read |

## Categories, search, spend, anomalies

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/categories` | Global plus space categories for the current space. Query: `spaceId?`. | read |
| GET | `/api/search` | Natural-language search; returns matching documents. Query: `q`, `spaceId?`. | read |
| GET | `/api/search/structured` | Search with the parsed structured filter exposed (for debugging or advanced clients). | read |
| GET | `/api/spend/by-category` | Spend totals grouped by category over a range. Query: `spaceId?`, date range. | read |
| GET | `/api/spend/by-month` | Spend totals grouped by month, with granularity. Query: `spaceId?`, granularity. | read |
| GET | `/api/spend/summary` | Combined spend summary (total, by category, currency, rates). Query: `spaceId?`. | read |
| GET | `/api/anomalies` | Confirmed documents flagged higher-than-usual for their category. Query: `spaceId?`. | read |

## Reminders

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/reminders` | List reminders; each row includes `documentFilename` (the linked document's file name, resolved in one batch query). Query: `spaceId?`, `status?`. | read |
| POST | `/api/reminders` | Create one. Query: `spaceId?`. Body: `{type, title?, remindOn, recurrence?, documentId?}`. | write |
| PATCH | `/api/reminders/{id}` | Edit type, title, date, recurrence, linked document. | write |
| POST | `/api/reminders/{id}/snooze` | Re-date to `days` from today (default 1; 0 reopens). Query: `days`. | write |
| POST | `/api/reminders/{id}/done` | Mark handled; a recurring reminder schedules its next occurrence. | write |
| POST | `/api/reminders/{id}/dismiss` | Dismiss ("never mind"). | write |

## Chat (Ask your vault)

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| POST | `/api/chat/ask` | Grounded answer with citations. Query: `spaceId?`. Body: `{question}`. Degrades to retrieval-only when the budget is spent. | read |
| POST | `/api/chat/reindex` | Embed any documents in the space missing a current-model vector. Query: `spaceId?`. | write |

## Spaces, members and invitations

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/spaces` | List the spaces the caller belongs to. | authenticated |
| POST | `/api/spaces` | Create a shared space. Body: `{name}`. | authenticated |
| PUT | `/api/spaces/{id}` | Rename or set description. | owner |
| DELETE | `/api/spaces/{id}` | Delete a shared space and all its documents. | owner |
| GET | `/api/spaces/{id}/members` | List members and their roles/status. | owner |
| POST | `/api/spaces/{id}/members` | Invite by email. Body: `{email, role}`. | owner |
| DELETE | `/api/spaces/{id}/members/{userId}` | Remove a member or dismiss a declined row. | owner |
| POST | `/api/spaces/{id}/members/{userId}/approve` | Approve a self-requested join. | owner |
| GET | `/api/spaces/{id}/join-link` | Get the current join link. | owner |
| POST | `/api/spaces/{id}/join-link/rotate` | Rotate the join token (invalidates the old link). | owner |
| DELETE | `/api/spaces/{id}/join-link` | Revoke the join link. | owner |
| POST | `/api/spaces/join` | Request to join via a join token. Body: `{token}`. | authenticated |
| GET | `/api/spaces/invitations` | List the caller's pending invitations. | authenticated |
| POST | `/api/spaces/{id}/invitations/accept` | Accept an invitation. | invitee |
| POST | `/api/spaces/{id}/invitations/decline` | Decline an invitation. | invitee |
| GET | `/api/spaces/{spaceId}/ingest-address` | Get the space's forward-to-file address. | owner |
| POST | `/api/spaces/{spaceId}/ingest-address/rotate` | Rotate the ingest token. | owner |

## Google Drive backup

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/integrations/google-drive/authorize-url` | Get the Google consent URL to link a Drive. Query: `spaceId`. | owner/member |
| GET | `/api/integrations/google-drive/connect` | Convenience redirect into the consent flow. | owner/member |
| GET | `/api/integrations/google-drive/callback` | OAuth redirect target; stores the encrypted refresh token. | public (OAuth) |
| GET | `/api/integrations/google-drive/status` | Connection(s), mode, per-Drive quota and sync state. Query: `spaceId?`. | read |
| POST | `/api/integrations/google-drive/sync` | Sync now. Query: `spaceId?`. | write |
| PUT | `/api/integrations/google-drive/mode` | Set rotate or mirror. Body: `{mode}`. | owner |
| POST | `/api/integrations/google-drive/connections/{connectionId}/activate` | Make a Drive the active one (rotate mode). | owner |
| DELETE | `/api/integrations/google-drive/connections/{connectionId}` | Unlink a Drive (files already backed up stay). | owner/linker |

## Backup, export and disaster recovery

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/export` | Download the full vault ZIP (manifest.json, data.csv, files/). Query: `spaceId?`. | read |
| POST | `/api/import` | Restore from an uploaded export ZIP. | write |
| POST | `/api/admin/rebuild` | Rebuild the database index by scanning the object store. | admin |
| POST | `/api/admin/pg-dump` | Run a database dump now. | admin |
| POST | `/api/admin/mirror` | Run the Backblaze B2 mirror now. | admin |
| GET | `/api/admin/backup-runs` | List recent backup and DR job outcomes. | admin |

## Integrity and usage

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/integrity/report` | Live three-tier verification of a space. Query: `spaceId?`. | read |
| GET | `/api/integrity/history` | Past integrity check results. | read |
| GET | `/api/ai-usage` | The shared daily AI budget and spend (global and this user), in neurons and tokens. | authenticated |

## Ingestion (forward-to-file)

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| POST | `/api/ingest/email` | Inbound email webhook; routes attachments to a space by its ingest token and runs the upload pipeline. | public (shared secret + per-space token) |
| POST | `/api/ingest/whatsapp` | Inbound WhatsApp webhook. | public (shared secret) |
| GET | `/api/ingest/whatsapp` | Webhook verification handshake. | public |

## Health

| Method | Path | Purpose | Access |
| --- | --- | --- | --- |
| GET | `/api/health` | Liveness check. | public |
