-- What each build step's agent said it decided (M4 slice 8, §6.8): one row per step and run, written when the run's ready
-- result is applied. summary is the StepSummary JSON, Tink-encrypted under an AAD naming the item, generation, step and
-- run: the agent wrote it and it may quote the repository. A ready without one is a row in state 'summary_missing' with
-- no summary, so the steps view can say the step recorded no decisions instead of showing nothing. A redelivered ready
-- inserts nothing: the key is the row's identity. step_index is 0 until the step cursor exists (slice 9).
CREATE TABLE work_step_summary (
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation   BIGINT NOT NULL,
    step_index   INT NOT NULL CHECK (step_index >= 0),
    run_id       TEXT NOT NULL,
    summary      BYTEA,
    state        TEXT NOT NULL CHECK (state IN ('recorded', 'summary_missing')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (work_item_id, generation, step_index, run_id),
    CHECK ((state = 'recorded') = (summary IS NOT NULL))
);
