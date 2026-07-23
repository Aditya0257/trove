-- ============================================================================
--  V13 — membership invite lifecycle
-- ============================================================================
--  Invites become a request the invited user accepts or declines, instead of
--  adding them silently. `status` gates access (only 'active' can use a space);
--  'pending' is an outstanding invite, 'declined' a refusal the owner can dismiss.
--  `invited_by` records who sent it. Existing rows are established members, so they
--  default to 'active'.
-- ============================================================================
alter table space_member add column if not exists status varchar(16) not null default 'active';
alter table space_member add column if not exists invited_by uuid;

-- Speeds up "my pending invitations" and per-space roster lookups.
create index if not exists idx_space_member_user_status on space_member (user_id, status);
