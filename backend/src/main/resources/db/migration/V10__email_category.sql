-- ============================================================================
--  V10 — add the "email" category (Important-Emails feature)
-- ============================================================================
--  People screenshot important emails (tax paid, subscription renewed) and later
--  can't find which inbox/thread they were in. Trove files those screenshots under
--  a first-class "email" category, tagged with the account/subject/date so they're
--  searchable. Global (space_id NULL = shared by all spaces).
-- ============================================================================
insert into category (space_id, code, label) values
    (null, 'email', 'Email')
on conflict (space_id, code) do nothing;
