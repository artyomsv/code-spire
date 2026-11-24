-- Review waits for CI (docs/superpowers/specs/2026-11-24-review-waits-for-ci-design.md).
--
-- start_review: when a review of a new head starts. IMMEDIATELY is the rule every repository that exists today
-- reviews under; AFTER_CHECKS waits until every check on the head has finished, starts the review on green, skips it
-- on red (no review, no charge) and starts a non-approving review after 60 minutes of pending checks. GitHub only: a
-- forge with no check read reviews at once whatever this says.
--
-- The work-item change triggers (V104) compare the whole repository row, so this column needs no trigger of its own.
ALTER TABLE repository ADD COLUMN start_review TEXT NOT NULL DEFAULT 'IMMEDIATELY'
    CONSTRAINT repository_start_review_known CHECK (start_review IN ('IMMEDIATELY', 'AFTER_CHECKS'));

-- One row per review that waits for checks on a head, or whose review started before its checks finished.
-- state 'waiting': nothing has run; the webhook wake-up and the sweep read the head's checks. Taking the row (a DELETE
-- or an UPDATE guarded on state = 'waiting') is the permit to start or skip, so a duplicate wake-up starts one review.
-- state 'timed_out': the review started after 60 minutes of pending checks; its verdict never approves. A new head's
-- wait, or a start on an IMMEDIATELY repository, replaces the row.
-- next_read_at is the sweep's due time: 60 s after the wait starts (the grace read), then every 5 minutes. It is
-- pushed forward in the statement that claims the row, so two sweeps never read one wait at once.
CREATE TABLE review_checks_wait (
    review_id     VARCHAR(512) PRIMARY KEY REFERENCES review_status (review_id) ON DELETE CASCADE,
    repository_id UUID        NOT NULL,
    head_commit   TEXT        NOT NULL,
    untrusted     BOOLEAN     NOT NULL,
    state         TEXT        NOT NULL CONSTRAINT review_checks_wait_state_known CHECK (state IN ('waiting', 'timed_out')),
    waiting_since TIMESTAMPTZ NOT NULL,
    next_read_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX review_checks_wait_due ON review_checks_wait (next_read_at) WHERE state = 'waiting';
CREATE INDEX review_checks_wait_head ON review_checks_wait (repository_id, head_commit) WHERE state = 'waiting';
