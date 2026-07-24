-- =============================================================================
--  V20 - password reset tokens + TOTP two-factor
-- =============================================================================
--  Purpose:        let a user reset a forgotten password via an emailed link, and
--                  optionally protect login with an authenticator-app code (TOTP).
--  Business use:    account recovery without support, and a free second factor for a
--                  vault of sensitive documents.
--  Design:         reset tokens are stored HASHED (sha256) and are single-use + short
--                  lived, so a leaked DB never exposes a usable link. The TOTP secret is
--                  AES-GCM encrypted at rest (same EncryptionService as Drive tokens);
--                  totp_enabled gates the login challenge. SMS is deliberately NOT used
--                  (it costs money) - only free, offline authenticator apps.
-- =============================================================================

create table password_reset_token (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references app_user(id) on delete cascade,
    token_hash  text not null,                 -- sha256 hex of the emailed token (never raw)
    expires_at  timestamptz not null,
    used_at     timestamptz,                   -- set when consumed; blocks reuse
    created_at  timestamptz not null default now()
);
create index idx_prt_token_hash on password_reset_token (token_hash);
create index idx_prt_user on password_reset_token (user_id);

alter table app_user add column totp_secret_enc text;                    -- AES-GCM(Base32 secret)
alter table app_user add column totp_enabled boolean not null default false;
