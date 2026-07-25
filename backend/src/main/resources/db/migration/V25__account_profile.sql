-- Account profile additions.
--
-- avatar_key:    R2 object key of an optional profile photo (the image itself lives in
--                object storage, never in the DB, per the "no blobs in Postgres" rule).
--                NULL means the user has no photo and the UI shows an initials fallback.
-- pending_email: a not-yet-confirmed new sign-in email during an email change. The live
--                email column stays authoritative until the OTP sent to pending_email is
--                verified, at which point pending_email is promoted and cleared.
ALTER TABLE app_user ADD COLUMN avatar_key    text;
ALTER TABLE app_user ADD COLUMN pending_email text;
