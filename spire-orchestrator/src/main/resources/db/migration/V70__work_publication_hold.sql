ALTER TABLE factory_run DROP CONSTRAINT factory_run_status_closed;
ALTER TABLE factory_run ADD CONSTRAINT factory_run_status_closed
    CHECK (status IN ('queued','running','awaiting_delivery','succeeded','delivered_nothing','delivered_unfinished',
                      'failed','push_gate_refused','cancelled','dispatch_uncertain'));
ALTER TABLE factory_run DROP CONSTRAINT factory_run_ended_when_terminal;
ALTER TABLE factory_run ADD CONSTRAINT factory_run_ended_when_terminal
    CHECK ((status IN ('queued','running','dispatch_uncertain','awaiting_delivery')) = (ended_at IS NULL));
ALTER TABLE factory_run ADD COLUMN work_ready_at TIMESTAMPTZ;
ALTER TABLE factory_run ADD COLUMN checkpoint_head TEXT CHECK (checkpoint_head ~ '^[0-9a-f]{40}$');
ALTER TABLE factory_run ADD COLUMN active_wall_seconds BIGINT CHECK (active_wall_seconds >= 0);

-- Build readiness and terminal publication are separate deliveries, never first-result-wins aliases.
ALTER TABLE work_run_effect ADD COLUMN ready_payload BYTEA;
ALTER TABLE work_run_effect ADD COLUMN ready_processed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE work_run_effect ADD COLUMN preparation_binding TEXT CHECK (preparation_binding ~ '^[0-9a-f]{64}$');

CREATE TABLE work_delivery_effect (
    attempt_id UUID PRIMARY KEY REFERENCES work_phase_attempt(id),
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    run_id TEXT NOT NULL REFERENCES factory_run(run_id),
    state TEXT NOT NULL CHECK (state IN ('pending','publishing','pushed','proposing','delivered','refused')),
    permit BYTEA,
    permit_expires_at TIMESTAMPTZ,
    request BYTEA,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state NOT IN ('publishing','pushed','proposing','delivered') OR permit IS NOT NULL),
    CHECK (state NOT IN ('proposing','delivered') OR request IS NOT NULL)
);
CREATE INDEX work_delivery_pending ON work_delivery_effect(created_at) WHERE state NOT IN ('delivered','refused');
