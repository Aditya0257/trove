-- =============================================================================
--  V16 — remember each connected Drive's storage capacity
-- =============================================================================
--  Purpose:        cache the storage quota (total + used) of the Google account behind
--                  a drive_connection, read from Drive's about.get storageQuota.
--  Business use:    show the owner "Trove is using X of Y on this Drive" and, once
--                  Drive pooling lands, decide when an account is near full and the
--                  next connected Drive should take over (capacity rotation, Phase 3).
--  Design:         nullable bigints (bytes). limit is null for unlimited/Workspace
--                  accounts — the UI treats null limit as "unlimited". Refreshed on
--                  every connect and every sync (cheap: one about.get call).
-- =============================================================================

alter table drive_connection add column storage_limit_bytes bigint;   -- total Drive quota (null = unlimited)
alter table drive_connection add column storage_usage_bytes bigint;   -- bytes used across the whole account
alter table drive_connection add column quota_checked_at timestamptz;  -- when we last read the quota
