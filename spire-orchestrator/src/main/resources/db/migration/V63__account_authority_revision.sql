-- A monotonic revision for account configuration/credential edits. Advisory checks and
-- observed actor labels do not change authority and do not advance this revision.
ALTER TABLE scm_provider ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
