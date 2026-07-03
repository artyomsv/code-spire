-- The append-only event log: the versioned source of truth (DATA-MODEL §3).
-- Doubles as the transactional outbox once the Kafka dispatcher lands (P1).
CREATE TABLE event_log (
    global_position BIGSERIAL PRIMARY KEY,
    event_id        UUID        NOT NULL UNIQUE,
    stream_id       TEXT        NOT NULL,
    sequence        BIGINT      NOT NULL,
    event_type      TEXT        NOT NULL,
    event_version   INT         NOT NULL DEFAULT 1,
    payload         BYTEA       NOT NULL,
    -- Tink key id for payload encryption; 'none' until the Tink converter
    -- lands (P1). Column present from day one so rotation needs no migration.
    key_id          TEXT        NOT NULL DEFAULT 'none',
    correlation_id  TEXT,
    causation_id    UUID,
    actor           TEXT        NOT NULL DEFAULT 'system',
    occurred_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Optimistic concurrency: single-writer per stream (ADR-010).
    CONSTRAINT uq_stream_sequence UNIQUE (stream_id, sequence)
);

CREATE INDEX idx_event_log_stream ON event_log (stream_id, sequence);

-- Dispatcher/projector progress (P1 Kafka tailer; created now, schema-stable).
CREATE TABLE projection_checkpoint (
    name          TEXT PRIMARY KEY,
    last_position BIGINT NOT NULL DEFAULT 0
);

-- Consumer idempotency dedup (at-least-once, ADR-013).
CREATE TABLE consumed_event (
    consumer  TEXT NOT NULL,
    event_id  UUID NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
