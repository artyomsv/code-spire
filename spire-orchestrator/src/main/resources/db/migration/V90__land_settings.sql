-- How a repository lands a delivered pull request (M4 land, spec section 7.1).
--
-- Separate from work_repository_policy, and separate is the decision, for the reason V73 gives for the
-- build setup. A policy is about autonomy and limits, and a label may only ever NARROW the ceiling
-- (FR-F23). These three are facts about the repository -- how GitHub is asked to merge, what happens to
-- the ticket afterwards, how many agent fixes a refused merge may spend. A label that could choose them
-- would be choosing what a land spends and what it does to the tracker, which is the operator's call.
--
-- Separate from repository_build_defaults too: a build setup is required before a task can be
-- prepared, and these are not. No row means the operator's defaults (merge, comment, 3), read in code,
-- so a repository set up before land existed lands the way the operator decided without a backfill.
--
-- revision exists only so two operators cannot silently overwrite each other, as in V73.
CREATE TABLE repository_land_settings (
    repository_id UUID        PRIMARY KEY REFERENCES repository(id),
    revision      BIGINT      NOT NULL DEFAULT 1,
    merge_method  TEXT        NOT NULL CHECK (merge_method IN ('merge', 'squash', 'rebase')),
    -- comment: keep the ticket's status and post that the work is merged, with the PR link.
    -- close: close the ticket.
    ticket_rule   TEXT        NOT NULL CHECK (ticket_rule IN ('comment', 'close')),
    -- 0 hands every refusal to the operator.
    fix_attempts  INT         NOT NULL CHECK (fix_attempts BETWEEN 0 AND 10),
    -- The operator who last saved, as the OIDC subject, as in V73.
    updated_by    TEXT        NOT NULL CHECK (btrim(updated_by) <> ''),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
