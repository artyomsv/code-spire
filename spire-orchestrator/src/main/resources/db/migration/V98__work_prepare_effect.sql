-- The durable effect of a generated item's specification or plan run (M4 slice 3, A4). One row per draft attempt,
-- inserted in the transaction that appends the PHASE_STARTED entering spec or plan; a dispatcher claims it, and an
-- uncertain dispatch is recovered by reading, never sent again. It binds a provisional digest (setup revision, ticket
-- and kind, written by the dispatcher's claim), never a preparation binding: none exists until both drafts are
-- approved. note, validation_error and result_payload may quote the ticket or the repository and are
-- Tink-encrypted under an AAD naming the attempt.
CREATE TABLE work_prepare_effect (
    attempt_id         UUID PRIMARY KEY,
    work_item_id       TEXT NOT NULL REFERENCES work_item(id),
    generation         BIGINT NOT NULL,
    kind               TEXT NOT NULL CHECK (kind IN ('SPEC', 'PLAN')),
    state              TEXT NOT NULL CHECK (state IN ('pending', 'uncertain', 'sent', 'refused', 'done')),
    reason             TEXT,
    run_id             TEXT,
    provisional_digest CHAR(64),
    setup_revision     BIGINT,
    retry_of           UUID,
    note               BYTEA,
    validation_error   BYTEA,
    result_payload     BYTEA,
    result_processed   BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX work_prepare_effect_pending ON work_prepare_effect (created_at) WHERE state = 'pending';
CREATE INDEX work_prepare_effect_item ON work_prepare_effect (work_item_id, generation);

-- How the item prepares its work, recorded when it was admitted (decision 2, A16 over A5): a repository switched
-- later changes only items admitted after the switch. The copy sweep requires 'composed' here, never the
-- repository's current setting. Projected from the draft fact; every existing item was admitted composed.
ALTER TABLE work_item ADD COLUMN preparation_mode TEXT NOT NULL DEFAULT 'composed'
    CHECK (preparation_mode IN ('composed', 'generated'));

-- A 'draft' tracker row is the ticket comment carrying a written specification or plan (slice 6). It is bound to the
-- generation and the draft attempt, never to the item revision: under spec auto the approval follows the written
-- artifact at once, and a 'phase' row would be refused work_item_changed before it was sent.
ALTER TABLE work_tracker_outbox DROP CONSTRAINT IF EXISTS work_tracker_outbox_effect_class_check;
ALTER TABLE work_tracker_outbox ADD CONSTRAINT work_tracker_outbox_effect_class_check
    CHECK (effect_class IN ('phase', 'post_land', 'draft'));
