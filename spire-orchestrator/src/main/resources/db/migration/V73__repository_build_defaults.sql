-- How a repository builds: the coordinates a prepared task copies (M3.5 part B).
--
-- Separate from work_repository_policy, and separate is the decision. A policy is about autonomy and
-- limits, and a label may only ever NARROW the ceiling (FR-F23). These three are deployment facts --
-- which agent image runs, which model it calls, which tree it starts from. A label that could choose
-- them would be choosing what a run spends, which NFR-F2 reserves to the operator.
--
-- The values are COPIED into a preparation, and the gate binds that copy (ADR-045), so editing this row
-- never changes what an open decision approves. That is why no version history lives here: the copy
-- that matters is in the preparation. revision exists only so two operators cannot silently overwrite
-- each other, the same way work_repository_policy.revision does.
--
-- The model is stored by NAME, not by catalog id. The name is what a dispatch sends to the provider
-- (WorkRunAssembly), and a catalog row deleted and recreated under the same name still names the same
-- model at the vendor. An unknown or disabled name is refused when it is saved AND again before a run
-- starts, because a catalog can change between the two.
--
-- No billing column yet: paying by subscription arrives with part F, and a column whose only accepted
-- value is API_KEY would be a choice the deployment cannot honour.
CREATE TABLE repository_build_defaults (
    repository_id UUID        PRIMARY KEY REFERENCES repository(id),
    revision      BIGINT      NOT NULL DEFAULT 1,
    base_branch   TEXT        NOT NULL CHECK (btrim(base_branch) <> ''),
    harness       TEXT        NOT NULL CHECK (btrim(harness) <> ''),
    model         TEXT        NOT NULL CHECK (btrim(model) <> ''),
    -- The operator who last saved, as the OIDC subject. Not an e-mail address: identity is a stable id
    -- here as everywhere else.
    updated_by    TEXT        NOT NULL CHECK (btrim(updated_by) <> ''),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
