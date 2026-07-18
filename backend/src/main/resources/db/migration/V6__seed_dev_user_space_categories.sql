-- =============================================================================
--  V6 — seed dev identity + global categories  (DECISIONS.md → D6)
-- =============================================================================
--  Purpose:        create ONE user, their personal space + owner membership, and
--                  the global category taxonomy, all with FIXED UUIDs so the API
--                  can operate without a login flow during Slice 1.
--  Business use:    lets the upload → list → confirm flow be tested end-to-end
--                  before real auth exists. Full auth/spaces are a later phase.
--  Design:         requirements call for minimal auth in this slice ("a single
--                  seeded user and one personal space is fine"). These fixed IDs
--                  match trove.dev.* in application.yml.
--  Reasoning:      global categories use space_id = NULL so every space shares them.
--                  'uncategorized' is the provisional filing bucket used at upload
--                  time before extraction resolves the real category (D4). 'shopping'
--                  must exist because StubExtractionProvider returns it (DESIGN §6.2).
--  Safety:         idempotent-ish via ON CONFLICT so re-running is harmless.
-- =============================================================================

-- Seeded user. password_hash is a non-login placeholder; real auth comes later.
insert into app_user (id, email, display_name, password_hash)
values ('00000000-0000-0000-0000-000000000001',
        'dev@trove.local', 'Dev User', 'SEED-NO-LOGIN')
on conflict (id) do nothing;

-- Their personal space.
insert into space (id, name, kind, created_by)
values ('00000000-0000-0000-0000-000000000010',
        'Dev Personal', 'personal', '00000000-0000-0000-0000-000000000001')
on conflict (id) do nothing;

-- Owner membership linking the two.
insert into space_member (space_id, user_id, role)
values ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001', 'owner')
on conflict (space_id, user_id) do nothing;

-- Global category taxonomy (space_id NULL = shared by all spaces).
insert into category (space_id, code, label) values
    (null, 'uncategorized', 'Uncategorized'),
    (null, 'shopping',      'Shopping'),
    (null, 'electricity',   'Electricity'),
    (null, 'water',         'Water'),
    (null, 'gas',           'Gas'),
    (null, 'internet',      'Internet'),
    (null, 'mobile',        'Mobile / Phone'),
    (null, 'insurance',     'Insurance'),
    (null, 'medical',       'Medical'),
    (null, 'travel',        'Travel'),
    (null, 'food',          'Food & Dining'),
    (null, 'rent',          'Rent'),
    (null, 'subscription',  'Subscription'),
    (null, 'tax',           'Tax'),
    (null, 'other',         'Other')
on conflict (space_id, code) do nothing;
