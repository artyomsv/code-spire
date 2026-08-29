-- What a team keeps dismissing, once an operator has agreed it is a preference
-- (P4 / FR-10, ADR-027).
--
-- A nightly job groups judged findings and proposes; an admin approves, rejects or
-- scopes. Only an APPROVED row ever changes a review, and what it does is filter
-- matching findings AFTER generation, with the count shown -- never steer the prompt,
-- because nothing can tell whether a model honoured an instruction and a finding it
-- silently skipped leaves no trace.

CREATE TABLE learned_preference (
    id            BIGSERIAL    PRIMARY KEY,
    -- 'global' or 'repo'. A repo-scoped row carries workspace/slug in scope_value.
    scope_type    VARCHAR(16)  NOT NULL,
    scope_value   VARCHAR(512) NOT NULL DEFAULT '',
    category      VARCHAR(32)  NOT NULL,
    -- A normalised glob from a fixed ladder, never a literal path: a preference about
    -- one file is not a preference. The ladder must be deterministic across nights,
    -- because "a rejected proposal is not regenerated" depends on the group identity
    -- being recognisable tomorrow.
    path_glob     VARCHAR(512) NOT NULL,
    severity      VARCHAR(16)  NOT NULL,
    state         VARCHAR(16)  NOT NULL,   -- PROPOSED | APPROVED | REJECTED
    -- The numbers the proposal was made on, shown on the card. A proposal whose
    -- evidence is not visible is the rung-2 gate's failure recurring: a conclusion
    -- drawn from a corpus too thin to speak, which nobody could see was thin.
    evidence_total     INT     NOT NULL DEFAULT 0,
    evidence_dismissed INT     NOT NULL DEFAULT 0,
    proposed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ,
    decided_by    VARCHAR(255)
);

-- One row per group. The nightly job upserts on this, so a group already PROPOSED is
-- refreshed rather than duplicated -- and a REJECTED group is recognised and left
-- alone rather than proposed again every night.
CREATE UNIQUE INDEX learned_preference_group
    ON learned_preference (scope_type, scope_value, category, path_glob, severity);

-- The read the filter makes on every review: which preferences are live.
CREATE INDEX idx_learned_preference_active ON learned_preference (state, scope_type, scope_value);

-- Now that the table exists, a suppressed finding can name what hid it. ON DELETE SET
-- NULL rather than CASCADE: revoking a preference must not delete the evidence that it
-- was wrong.
ALTER TABLE review_finding
    ADD CONSTRAINT review_finding_suppressed_by_fkey
    FOREIGN KEY (suppressed_by) REFERENCES learned_preference(id) ON DELETE SET NULL;
