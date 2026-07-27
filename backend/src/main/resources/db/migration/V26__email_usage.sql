-- ============================================================================
--  V26 — email_usage: per-UTC-day count of outbound emails sent
-- ============================================================================
--  Brevo's free tier is 300 emails/day, shared by the whole app. We record how
--  many we've sent today so the sender can stop at the cap (and not silently burn
--  it) and the Developer gauge can show the remaining daily allowance. One row per
--  UTC day; resets naturally when the day rolls over (00:00 UTC).
-- ============================================================================
create table if not exists email_usage (
    day  date    primary key,
    sent integer not null default 0
);
