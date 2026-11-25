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

-- One row per review: its latest admitted run, identified by its round (the count of ReviewRequested folded when the
-- run was accepted, so a re-run on the same head is another run), for a repository that waits for checks. The row is
-- written in the round lock that admits the run and is never replaced by an earlier round, so it is the durable
-- admission state every later write fences on.
--   admitted            the run was accepted and its review header is still being set up; nothing reads it yet.
--   waiting             nothing has run; the webhook wake-up and the sweep read the head's checks.
--   starting            the review was claimed to start; its FetchDiff is sent, then the row becomes started.
--   starting_unfinished the same after the 60-minute timeout; the row then becomes timed_out.
--   skipping            the checks failed; the run is ended and noted, then the row becomes skipped.
--   started             the run's review started (at once, or after its checks); terminal.
--   skipped             the run was skipped on red checks; terminal.
--   timed_out           the review started before its checks finished, so its verdict never approves; terminal.
-- Moving a waiting row to a claimed state (guarded on state = 'waiting' and the round) is the permit to act, so only
-- one resolver ever acts on a run. A claimed row is the recoverable intent: a process that dies before the outcome is
-- complete leaves it for the sweep, which finishes it. A later run's admission replaces the row.
-- next_read_at is the sweep's due time: 60 s after the wait starts (the grace read), then every 5 minutes; for a
-- claimed row, 5 minutes after the claim. It is pushed forward in the statement that claims the row for a sweep.
-- failed_checks names the failed checks of a skip, so a skip finished after a restart still says which.
-- read_failure is why the last read of the head's checks failed (an HTTP status, when the forge gave one), NULL after
-- a read that answered: a waiting row with one raises REVIEW_CHECKS_UNREADABLE, and a review started on the timeout
-- with one says its checks could not be read rather than that they were still pending.
CREATE TABLE review_checks_wait (
    review_id     VARCHAR(512) PRIMARY KEY REFERENCES review_status (review_id) ON DELETE CASCADE,
    repository_id UUID        NOT NULL,
    head_commit   TEXT        NOT NULL,
    round         INT         NOT NULL,
    untrusted     BOOLEAN     NOT NULL,
    state         TEXT        NOT NULL CONSTRAINT review_checks_wait_state_known
        CHECK (state IN ('admitted', 'waiting', 'starting', 'starting_unfinished', 'skipping', 'started', 'skipped', 'timed_out')),
    waiting_since TIMESTAMPTZ NOT NULL,
    next_read_at  TIMESTAMPTZ NOT NULL,
    failed_checks TEXT,
    read_failure  TEXT
);

CREATE INDEX review_checks_wait_due ON review_checks_wait (next_read_at)
    WHERE state IN ('admitted', 'waiting', 'starting', 'starting_unfinished', 'skipping');
CREATE INDEX review_checks_wait_head ON review_checks_wait (repository_id, head_commit) WHERE state = 'waiting';

-- run_round: the round of the run whose header this row holds. Every accepted run writes it with its status and run
-- token (a run started at once, a re-run, a waiting run's header and its readying). A run's header is initialised once
-- per round: only while the row's run_round is earlier than its own, evaluated on the locked row (a readying that
-- finds its round already set only shows the run waiting). So a run whose caller paused never replaces the header of
-- this or a later run, whatever the repository's start setting. NULL: no run since V121.
ALTER TABLE review_status ADD COLUMN run_round INT;
