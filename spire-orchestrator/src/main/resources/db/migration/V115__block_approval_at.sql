-- Block approval at: a per-repository approval threshold
-- (docs/superpowers/specs/2026-11-14-approval-threshold-design.md, ADR-047).
--
-- block_approval_at: the lowest severity whose open finding makes the review's assessment Request changes and holds
-- a factory land. BLOCKER counts open BLOCKER findings only, which is the rule every repository that exists today
-- reviews under; MAJOR adds MAJOR, MINOR adds MAJOR and MINOR. INFO and NIT never block, so they are not values.
-- "Reviewer may approve" (V88) still decides whether the assessment is posted as a verdict.
--
-- The work-item change triggers (V104) compare the whole repository row, so this column needs no trigger of its own.
ALTER TABLE repository ADD COLUMN block_approval_at TEXT NOT NULL DEFAULT 'BLOCKER'
    CONSTRAINT repository_block_approval_at_known CHECK (block_approval_at IN ('BLOCKER', 'MAJOR', 'MINOR'));
