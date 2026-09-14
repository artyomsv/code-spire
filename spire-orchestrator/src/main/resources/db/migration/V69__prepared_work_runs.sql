ALTER TABLE factory_run ADD COLUMN work_phase_attempt_id UUID REFERENCES work_phase_attempt(id);
CREATE UNIQUE INDEX factory_run_work_attempt ON factory_run(work_phase_attempt_id) WHERE work_phase_attempt_id IS NOT NULL;
CREATE TABLE work_run_effect (
    attempt_id UUID PRIMARY KEY REFERENCES work_phase_attempt(id),
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('pending','uncertain','sent','refused')),
    run_id TEXT UNIQUE REFERENCES factory_run(run_id),
    reason TEXT,
    result_payload BYTEA,
    result_processed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX work_run_effect_pending ON work_run_effect(created_at) WHERE state='pending';
