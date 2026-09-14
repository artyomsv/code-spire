-- Previously reserved gate rows had no decision binding and cannot authorize a continuation.
ALTER TABLE work_item_gate DROP CONSTRAINT work_item_gate_state_check;
UPDATE work_item_gate SET state=CASE state WHEN 'approved' THEN 'APPROVED' WHEN 'denied' THEN 'REJECTED'
    WHEN 'expired' THEN 'EXPIRED' ELSE 'SUPERSEDED' END;
ALTER TABLE work_item_gate ADD CONSTRAINT work_item_gate_state_check CHECK (state IN ('OPEN','APPROVED','REJECTED','EXPIRED','SUPERSEDED'));
ALTER TABLE work_item_gate ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE work_item_gate ADD COLUMN item_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE work_item_gate ADD COLUMN policy_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE work_item_gate ADD COLUMN artifact TEXT;
ALTER TABLE work_item_gate ADD COLUMN opened_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE work_item_gate ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE work_item_gate ADD COLUMN resolver TEXT;
ALTER TABLE work_item_gate ADD COLUMN channel TEXT;
ALTER TABLE work_item_gate ADD COLUMN answer_key TEXT;
ALTER TABLE work_item_gate ADD COLUMN note BYTEA;
CREATE UNIQUE INDEX work_item_gate_open ON work_item_gate(work_item_id,generation,phase) WHERE state='OPEN';
CREATE INDEX work_item_gate_expiry ON work_item_gate(expires_at) WHERE state='OPEN';
ALTER TABLE work_item ADD COLUMN slot_reserved BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE work_phase_attempt (
    id UUID PRIMARY KEY,
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    phase TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('started','completed')),
    started_at TIMESTAMPTZ NOT NULL,
    UNIQUE(work_item_id,generation,phase)
);
