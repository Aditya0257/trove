-- =============================================================================
--  V23 — reminder lifecycle: titles, recurrence, and a 'done' state
-- =============================================================================
--  Purpose:        make reminders first-class to live with day to day - give them
--                  a human title, let them repeat, and let a user mark one handled
--                  (distinct from dismissing it).
--  Business use:    "Rent - pay landlord" every month; mark this month done and the
--                  next occurrence is scheduled automatically. "Done" means handled;
--                  "dismissed" means never mind.
--  Design:         additive and back-compatible. status stays free text (no CHECK)
--                  so adding 'done' needs no constraint change; recurrence defaults
--                  to 'none' so every existing reminder keeps its current behaviour.
-- =============================================================================

alter table reminder add column title        text;
alter table reminder add column recurrence   text not null default 'none';
alter table reminder add column completed_at  timestamptz;

comment on column reminder.title      is 'optional human label, e.g. "Rent - pay landlord"';
comment on column reminder.recurrence is 'none | weekly | monthly | quarterly | yearly';
comment on column reminder.completed_at is 'when the user marked it done';
comment on column reminder.status     is 'pending | sent | dismissed | done';
