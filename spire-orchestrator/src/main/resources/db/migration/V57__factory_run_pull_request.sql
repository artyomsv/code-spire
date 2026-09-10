-- What a run proposed, and what came of proposing it.
--
-- M2 shipped the PullRequestSink port, three forge adapters and the body builder, and nothing that
-- CALLS them: `pullRequestSink(...)` had no caller outside its own factory method, so every run
-- ended at a pushed branch. The first live run proved it -- the branch landed, the work was
-- correct, and no pull request existed. These four columns are what the step that closes that gap
-- needs to keep.
--
-- task_summary is written at DISPATCH, not derived at the end. The prompt is not persisted (it
-- rides the command and is deliberately not a column: a fix prompt is model-derived and can be
-- 64KB), but a pull request has to say what it is FOR, and by the time the run finishes the only
-- text left is a branch name and a list of paths. One bounded line is what the body builder asks
-- for and all it will print.
--
-- pr_number and pr_url are the outcome. Both, rather than one and a rule for building the other:
-- the number is what an operator quotes and what a later reconciliation would key on, and the URL
-- is per-forge -- GitHub's /pull/, GitLab's /-/merge_requests/, Bitbucket's /pull-requests/ -- so
-- deriving it here would put a fourth spelling of provider knowledge outside the adapters that own
-- it (ADR-020).
--
-- pr_error carries the failure. Opening a pull request happens AFTER the push succeeded, so it can
-- fail on its own -- a token narrowed since the push, a forge outage, a repository that forbids
-- them -- and the run is still a success by every measure that matters: the work is on the remote.
-- Flipping the run to failed would lie about the branch. Logging and moving on would be the silent
-- failure this project has paid for three times, so the reason is a column, next to the run whose
-- proposal it belongs to, where the runs list can show it.
ALTER TABLE factory_run
    ADD COLUMN task_summary text,
    ADD COLUMN pr_number    bigint,
    ADD COLUMN pr_url       text,
    ADD COLUMN pr_error     text;

-- A number and a URL travel together or not at all: half of an identity is worse than none, since
-- a reader that finds the number would build the wrong URL for the forge it is on.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_pr_is_whole
    CHECK ((pr_number IS NULL) = (pr_url IS NULL));

-- A forge numbers pull requests from one, per repository.
ALTER TABLE factory_run ADD CONSTRAINT factory_run_pr_number_positive
    CHECK (pr_number IS NULL OR pr_number > 0);
