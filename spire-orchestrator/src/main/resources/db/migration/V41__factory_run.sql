-- The factory's read model: one row per dispatched run. Archival, cost and phases arrive in later
-- milestones; M0 needs enough to answer "what happened to this run" without replaying anything.
--
-- A separate table from review_status, not a status value on it. A run and a review are different
-- things with different lifecycles — the same reason pr_state became its own column rather than a
-- value in status, and archival a third dimension rather than a fourth status.
CREATE TABLE factory_run (
    run_id          TEXT         PRIMARY KEY,
    provider_type   VARCHAR(32)  NOT NULL,
    workspace       TEXT         NOT NULL,
    slug            TEXT         NOT NULL,
    subject         TEXT         NOT NULL,
    attempt         INT          NOT NULL CHECK (attempt >= 1),
    status          VARCHAR(24)  NOT NULL,
    harness         VARCHAR(32)  NOT NULL,
    model           TEXT         NOT NULL,
    base_branch     TEXT         NOT NULL,
    base_commit     TEXT         NOT NULL,
    branch          TEXT         NOT NULL,
    -- ADR-037: the identity the run pushed as. Recorded, never inferred from an account name,
    -- because an account can be renamed or reassigned and an attribute written at authorship cannot.
    pushed_as       TEXT,
    pushed_ref      TEXT,
    -- A refusal names every blocked path, so an operator reading the row knows what tripped the
    -- gate without opening a container log that may already be gone.
    blocked_paths   TEXT,
    failure_cause   VARCHAR(32),
    failure_detail  TEXT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,

    -- The dispatched PROMPT is deliberately absent. It is a work item's text, it can quote source,
    -- and DATA-MODEL §5 keeps that class of content out of a queryable read model. What a run was
    -- ABOUT is the subject; what it was TOLD is between the command and the container.

    -- Closed set, for the reason the review statuses are: a typo'd literal in a writer would
    -- otherwise pass compilation and produce a row no filter matches and no badge renders.
    CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'push_gate_refused', 'cancelled')),
    -- push_gate_refused is NOT failed: the run did correct work that was deliberately not delivered,
    -- and a status saying otherwise sends an operator hunting for a bug. So a refusal must say what
    -- it refused, a failure must name its cause, and a run cannot both have pushed and been refused.
    CHECK (status <> 'push_gate_refused' OR blocked_paths IS NOT NULL),
    CHECK (status <> 'failed' OR failure_cause IS NOT NULL),
    CHECK (pushed_ref IS NULL OR blocked_paths IS NULL),
    -- A terminal row has an end time; a live one does not. Either half alone is a row that lies.
    CHECK ((status IN ('queued', 'running')) = (ended_at IS NULL))
);

CREATE INDEX factory_run_status_idx ON factory_run (status, started_at DESC);
CREATE INDEX factory_run_repo_idx ON factory_run (provider_type, workspace, slug, started_at DESC);
