-- Tracker writes are an outbox, separate from domain notifications sent to Kafka.
CREATE TABLE work_tracker_outbox (
    effect_id UUID PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    item_revision BIGINT NOT NULL,
    phase TEXT NOT NULL CHECK (phase <> ''),
    payload BYTEA NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','uncertain','sent','refused')),
    remote_id TEXT,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_at TIMESTAMPTZ
);
CREATE INDEX work_tracker_outbox_pending ON work_tracker_outbox(created_at) WHERE state IN ('pending','uncertain');
