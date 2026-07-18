-- =============================================================================
--  V9 — vital-document encryption flag
-- =============================================================================
--  Purpose:        marks whether a document's stored file bytes are encrypted at rest.
--  Business use:    vital documents (passport/Aadhaar/PAN/policies) are AES-encrypted
--                  in object storage; this flag tells the app to decrypt on read and
--                  to serve them via the decrypt-stream path instead of a signed URL.
--  Design:         defaults false (existing docs are plaintext). Set true when a doc
--                  is flagged vital (at upload or confirm).
-- =============================================================================

alter table document add column encrypted boolean not null default false;
