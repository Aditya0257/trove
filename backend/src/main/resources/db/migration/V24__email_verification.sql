-- =============================================================================
--  V24 — email verification (OTP) before an account reaches admin approval
-- =============================================================================
--  Purpose:        hold the one-time code a new user must enter to prove they own
--                  the email they signed up with, BEFORE the account is put in front
--                  of the admin for approval.
--  Business use:    only real, reachable email addresses get in - so password resets
--                  and reminder emails always land somewhere the person actually reads.
--  Design:          one active code per user (PK on user_id; resend overwrites it).
--                  The code is stored only as a SHA-256 hash, with a short expiry and a
--                  capped attempt count. A new sign-up starts in status 'unverified'
--                  (a status value, so existing active/pending accounts are unaffected).
-- =============================================================================

create table email_verification (
    user_id    uuid primary key references app_user(id) on delete cascade,
    code_hash  text        not null,
    expires_at timestamptz not null,
    attempts   int         not null default 0,
    created_at timestamptz not null default now()
);

comment on table email_verification is 'One active email-verification OTP per user; overwritten on resend.';
