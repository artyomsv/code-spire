-- Real GitHub reviews (ADR-047), live proof of 2026-10-16 on spire-test PR 55.
--
-- run_token: an opaque value for the current review run of a row. A claimed run and a re-run get a
-- fresh one; a C8 retry and a metadata refresh keep it. The worker marks the native review it posts
-- with the token, so its look-first before a post finds a half-posted review of THIS run only — a
-- re-run on the same head (which clears the worker's claims) posts a new review instead of reusing,
-- and then dismissing, the earlier run's. Rows that exist today get one on their next run.
ALTER TABLE review_status ADD COLUMN run_token TEXT;
