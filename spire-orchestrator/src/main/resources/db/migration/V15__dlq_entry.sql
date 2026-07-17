-- Persisted dead-letter records (cs.dlq) for inspection + manual replay. Payload is the raw JSON the
-- producer serialized; original_topic is inferred from the message type so replay re-publishes correctly.
CREATE TABLE dlq_entry (
    id             UUID        PRIMARY KEY,
    kafka_key      TEXT,
    message_type   TEXT,                       -- the polymorphic "type" (AnswerFollowUp, DiffFetched, …) or ''
    original_topic TEXT        NOT NULL,        -- cs.commands | cs.integration | cs.results | cs.events
    reason         TEXT,                        -- dead-letter-reason header, best-effort
    payload        TEXT        NOT NULL,        -- the raw record value (JSON)
    status         TEXT        NOT NULL DEFAULT 'pending',   -- pending | replayed | discarded
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX dlq_entry_status_idx ON dlq_entry (status, created_at DESC);
