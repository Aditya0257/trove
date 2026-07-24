-- =============================================================================
--  V22 - shareable "request to join" link for a space
-- =============================================================================
--  Purpose:        let a space owner hand out a link that lets someone REQUEST to join
--                  the space (owner still approves) - a lighter alternative to typing
--                  each member's email.
--  Business use:    "here's the link to our household space" - they open it, request
--                  access, and the owner approves (reusing the pending-member flow).
--  Design:         one revocable token per space (null = no active link). Opening the
--                  link never auto-joins: it only creates a PENDING membership the owner
--                  must approve, so a leaked link can't silently add anyone.
-- =============================================================================

alter table space add column join_token text;
create unique index idx_space_join_token on space (join_token) where join_token is not null;
