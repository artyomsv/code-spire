CREATE TABLE runworker.work_run (
    run_id TEXT PRIMARY KEY CHECK (run_id <> ''),
    binding TEXT NOT NULL CHECK (binding ~ '^[0-9a-f]{64}$'),
    execution BYTEA NOT NULL,
    unit_spec BYTEA,
    unit_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('building','ready','publishing','finished')),
    ready_result BYTEA,
    final_result BYTEA,
    permit BYTEA,
    permit_id UUID,
    ready_sent_at TIMESTAMPTZ,
    final_sent_at TIMESTAMPTZ,
    release_pending BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state NOT IN ('ready','publishing') OR ready_result IS NOT NULL),
    CHECK (state <> 'publishing' OR (permit IS NOT NULL AND permit_id IS NOT NULL)),
    CHECK (state <> 'finished' OR final_result IS NOT NULL)
);
CREATE INDEX work_run_outbox ON runworker.work_run(updated_at)
    WHERE (ready_result IS NOT NULL AND ready_sent_at IS NULL)
       OR (final_result IS NOT NULL AND final_sent_at IS NULL);
