-- No FK: control can arrive before the ordered execution command creates its retained row.
CREATE TABLE runworker.work_publication_revocation (
    run_id TEXT NOT NULL,
    binding TEXT NOT NULL CHECK (binding ~ '^[0-9a-f]{64}$'),
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,binding)
);
