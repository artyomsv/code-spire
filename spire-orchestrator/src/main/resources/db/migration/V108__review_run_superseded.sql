-- Harness reviews, step 4 (review of PR #367): a superseded round's review run is cancelled even when its dispatch is
-- still being funded and has no row yet. The supersede records the newest round it ended here, under the advisory lock
-- its dispatch shares; a dispatch for that round or an earlier one, admitted under the same lock, finds it and refuses.
--
-- Keyed by ROUND, not commit: a forced re-run supersedes round N and requests round N+1 on the same head, and a pull
-- request can return to an earlier head, so a commit-keyed record would refuse every later call on that head for ever.
-- The round is the count of ReviewRequested in the review's stream (ReviewRuns), read for a supersede at its own
-- position, so a listener that runs late still records the right round.
--
-- No close record: a reopened pull request keeps its review id. A close is review_status.pr_state, which
-- IntegrationSaga writes before it cancels and writes back to OPEN on the next event.
--
-- One row per review, raised and never lowered, so it grows with reviews and needs no sweep.
CREATE TABLE review_run_superseded (
    review_id        TEXT         PRIMARY KEY,
    superseded_round INT          NOT NULL CHECK (superseded_round > 0),
    superseded_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
