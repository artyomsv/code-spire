-- One row per verify attempt this worker claimed (M4). The claim precedes any container, the result is
-- written before it is sent, and a restart that finds a row still 'running' reports verify_could_not_run.
-- The command and the result are Tink-encrypted: a result's output tails can quote source.
CREATE TABLE runworker.work_verify (
    attempt_id UUID PRIMARY KEY,
    run_id TEXT NOT NULL CHECK (run_id <> ''),
    command BYTEA NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('running','finished')),
    result BYTEA,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state <> 'finished' OR result IS NOT NULL)
);
CREATE INDEX work_verify_unsent ON runworker.work_verify(created_at) WHERE state='finished' AND sent_at IS NULL;
CREATE INDEX work_verify_running ON runworker.work_verify(created_at) WHERE state='running';
