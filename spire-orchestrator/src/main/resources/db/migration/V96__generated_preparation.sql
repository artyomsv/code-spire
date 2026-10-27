-- How a repository prepares its work (M4 slice 3, §6.1): 'composed' copies the ticket as one step, as every
-- repository has until now; 'generated' writes a specification and a plan with short read-only runs on the
-- build's own harness, model and payment. Every existing row, and a repository with no row, reads composed,
-- so nothing spends until an operator switches it.
ALTER TABLE repository_build_defaults ADD COLUMN preparation TEXT NOT NULL DEFAULT 'composed'
    CHECK (preparation IN ('composed', 'generated'));

-- Which plan step a run built, null for a run that is not a plan step (A18).
--
-- No phase column, although §6.9 first proposed one: verify is not a run, and kind already names spec, plan,
-- build and fix. kind + step_index answers "which phase and which step" without a second column that could
-- disagree with the first.
ALTER TABLE factory_run ADD COLUMN step_index INT CHECK (step_index IS NULL OR step_index >= 0);
