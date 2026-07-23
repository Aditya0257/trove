-- ============================================================================
--  V14 — space description
-- ============================================================================
--  Owners can give a space a short bio/description (what it's for, who it's with).
--  Optional free text; null for spaces that never set one.
-- ============================================================================
alter table space add column if not exists description text;
