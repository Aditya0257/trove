-- ============================================================================
--  V12 — add the "bank" category
-- ============================================================================
--  Bank documents (statements, passbooks, cheque/challan copies, account letters)
--  are a distinct, common kind of thing people keep and search for. Give them a
--  first-class category so they file and filter cleanly alongside the rest.
--  Global (space_id NULL = shared by all spaces).
-- ============================================================================
insert into category (space_id, code, label) values
    (null, 'bank', 'Bank')
on conflict (space_id, code) do nothing;
