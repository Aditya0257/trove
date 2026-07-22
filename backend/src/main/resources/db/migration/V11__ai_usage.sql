-- ============================================================================
--  V11 — persistent AI usage accounting (neurons + tokens, per day)
-- ============================================================================
--  One Cloudflare Workers AI account backs the whole app; its free allowance is
--  10,000 neurons/day, shared by all users. This table records consumption per UTC
--  day, per user, plus a global aggregate row (the all-zero UUID), so the gauge
--  survives restarts and can show both the shared total and each user's slice.
--
--  Neurons are Cloudflare's real billed unit (GPU compute); tokens are recorded
--  alongside as the human-readable figure the API returns.
-- ============================================================================
create table ai_usage (
    day      date not null,
    user_id  uuid not null,   -- 00000000-0000-0000-0000-000000000000 = global aggregate
    neurons  double precision not null default 0,
    tokens   bigint not null default 0,
    primary key (day, user_id)
);
