-- M4 verify, slice 1 (docs/superpowers/specs/2026-10-07-factory-m4-verify-design.md).
--
-- The operator declares a repository's checks (spec §2): a verify command is control, and ADR-036 keeps
-- control out of repository free text. The values are COPIED into a preparation and bound by it (version 5),
-- so editing this row never changes what an open decision approved.
ALTER TABLE repository_build_defaults ADD COLUMN verify_commands TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE repository_build_defaults ADD COLUMN verify_timeout_seconds INTEGER NOT NULL DEFAULT 1800
    CHECK (verify_timeout_seconds > 0);

-- One row per verify attempt, like work_run_effect for builds. The claim precedes the command on the bus,
-- and the result is stored before it is applied. Its check tails can quote source, so the result is
-- Tink-encrypted (AAD work-verify-result:<attempt_id>).
CREATE TABLE work_verify_effect (
    attempt_id UUID PRIMARY KEY REFERENCES work_phase_attempt(id),
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    run_id TEXT NOT NULL REFERENCES factory_run(run_id),
    head TEXT NOT NULL CHECK (head ~ '^[0-9a-f]{40}$'),
    state TEXT NOT NULL CHECK (state IN ('pending','sent','uncertain','reported','applied','refused')),
    outcome TEXT CHECK (outcome IN ('PASSED','FAILED','UNVERIFIED')),
    reason TEXT,
    result BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state NOT IN ('reported','applied') OR (result IS NOT NULL AND outcome IS NOT NULL))
);
CREATE INDEX work_verify_open ON work_verify_effect(created_at) WHERE state IN ('pending','reported');
CREATE INDEX work_verify_item ON work_verify_effect(work_item_id, generation, created_at);

-- A retried build is a second build attempt in the same generation, and its verify a second verify (M4).
-- M3 allowed one attempt per phase per generation; what it protected is that two attempts of one phase
-- never run at once, so that is what remains: at most one STARTED attempt per phase per generation.
ALTER TABLE work_phase_attempt DROP CONSTRAINT work_phase_attempt_work_item_id_generation_phase_key;
CREATE UNIQUE INDEX work_phase_attempt_one_started ON work_phase_attempt(work_item_id, generation, phase) WHERE state='started';
