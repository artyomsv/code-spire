-- A review whose Review diff limit setting could not be read when its LLM credential was packed (PR 456).
--
-- Such a review is not failed and spends no retry attempt: it is deferred. review_status.retry_at is set a short
-- delay ahead (kept if one is already due earlier) and the retry sweeper re-runs its pipeline from FetchDiff, with
-- the attempt counter unchanged. A redelivered ContextAssembled can only write the same or an earlier due time, so a
-- duplicate cannot spend anything.
--
-- deferred_since bounds the deferral by time, not by count: the first failed read of a head writes it, later ones keep
-- it, and a failed read more than 60 minutes after it fails the review. A read that succeeds, and that terminal
-- failure, delete the row. commit_sha is the head the window belongs to; a failed read on another head starts a new
-- window, so a push never inherits the last head's clock.
CREATE TABLE review_limit_deferral (
    review_id      VARCHAR(512) PRIMARY KEY REFERENCES review_status (review_id) ON DELETE CASCADE,
    commit_sha     TEXT        NOT NULL,
    deferred_since TIMESTAMPTZ NOT NULL
);
