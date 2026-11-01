-- Harness reviews, step 4 (review of PR #367): a superseded head's review run is cancelled even when its dispatch is
-- still being funded and has no row yet. The supersede records the head it replaced here, under the advisory lock its
-- dispatch shares; a dispatch for that head, admitted under the same lock, finds the row and refuses. Without it the
-- cancellation found no row to cancel and the obsolete run started with no cancel claim.
--
-- No close intent: a reopened pull request keeps its review id, so one would block its reviews for ever. A close is
-- review_status.pr_state, which IntegrationSaga writes before it cancels and writes back to OPEN on the next event.
--
-- One row per superseded head of a review. A row is needed only while a dispatch for that head could still be in
-- flight, which is seconds; they are kept rather than swept because they are small and a sweep is a second moving part.
CREATE TABLE review_run_cancel (
    review_id    TEXT         NOT NULL,
    commit_sha   TEXT         NOT NULL CHECK (btrim(commit_sha) <> ''),
    cancelled_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, commit_sha)
);
