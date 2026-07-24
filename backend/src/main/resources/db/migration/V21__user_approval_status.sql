-- =============================================================================
--  V21 - admin-approved, closed registration
-- =============================================================================
--  Purpose:        gate new sign-ups behind admin approval so Trove stays a private,
--                  invite-only vault for a known circle - no open registration.
--  Business use:    a new person can register, but cannot sign in until the admin
--                  approves the request. Existing users are unaffected.
--  Design:         a status on each account. Every current row defaults to 'active'
--                  (they keep working); new self-service sign-ups are set to 'pending'
--                  in code and flip to 'active' on approval (or 'rejected'). The admin
--                  is identified by config (trove.admin.email), not a stored flag, so it
--                  can change without a migration.
-- =============================================================================

alter table app_user add column status text not null default 'active';   -- 'active' | 'pending' | 'rejected'
create index idx_app_user_status on app_user (status);
