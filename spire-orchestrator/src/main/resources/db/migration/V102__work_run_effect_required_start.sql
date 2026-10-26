-- The checkpoint a later plan step's build was dispatched to start from (M4 slice 3, A2, decision 11), recorded with the
-- dispatch claim. Set only when the start is required: the run worker never replaces it with the base. The orchestrator
-- refuses a ready whose start head is another one, and a CHECKPOINT_MISSING failure records required_start_run_id as
-- lost so no retry starts from it again. NULL for every other build, which may start from the base.
ALTER TABLE work_run_effect ADD COLUMN required_start_run_id TEXT;
ALTER TABLE work_run_effect ADD COLUMN required_start_head TEXT CHECK (required_start_head IS NULL OR required_start_head ~ '^[0-9a-f]{40}$');
ALTER TABLE work_run_effect ADD CONSTRAINT work_run_effect_required_start CHECK ((required_start_run_id IS NULL) = (required_start_head IS NULL));
