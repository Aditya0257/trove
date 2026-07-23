-- =============================================================================
--  V15 — capture WHICH Google account a space's Drive is connected to
-- =============================================================================
--  Purpose:        record the connected Google account's email + display name on
--                  each drive_connection, so the UI can show "connected as <email>".
--  Business use:    the first step toward Drive pooling (Phase 3) — an owner needs to
--                  see which account backs a space (and, later, which of several).
--                  Also lets us tell members apart when more than one Drive is linked.
--  Design:         plain nullable text columns, backfilled lazily on the next connect
--                  or sync (we read it from Drive's about.get under the existing
--                  drive.file scope — no new consent needed). connected_by already
--                  exists (V7) but was never populated; the callback now sets it.
-- =============================================================================

alter table drive_connection add column google_email text;         -- e.g. jane@gmail.com
alter table drive_connection add column google_account_name text;   -- e.g. "Jane Doe"
