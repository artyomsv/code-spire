ALTER TABLE work_item ADD COLUMN activity_polled_at TIMESTAMPTZ;
CREATE TABLE work_run_hold_outbox (
    run_id TEXT PRIMARY KEY REFERENCES factory_run(run_id),
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    build_attempt_id UUID NOT NULL,
    preparation_binding TEXT NOT NULL CHECK (preparation_binding ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sent_at TIMESTAMPTZ
);
CREATE TABLE work_activity_receipt (
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    channel TEXT NOT NULL,
    delivery_id TEXT NOT NULL,
    outcome TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(work_item_id,channel,delivery_id)
);
