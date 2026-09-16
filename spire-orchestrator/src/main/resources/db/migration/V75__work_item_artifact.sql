-- The specification and the one-step plan this deployment composed for a work item (M3.5 part C).
--
-- Until now both had to be tickets a person wrote, and the digest was over a ticket body re-read before
-- every use (WorkArtifacts.observe). That is what made the operator create three issues for one task and
-- paste JSON into one of them. When the system composes them instead, the bytes it approved must be the
-- bytes it builds -- so they are stored here, and the digest is over THESE rows.
--
-- INSERT ONLY. Nothing updates or deletes a row:
--
--   * re-admission carries a preparation into the next generation (WorkItemEvent.readmit), and
--   * re-preparing inside one generation writes a NEW preparation,
--
-- so a row replaced in place would destroy the exact bytes an open gate, or a held run, still binds. A
-- new preparation writes new rows and leaves the old ones for whatever still points at them. A sweep
-- that loses a race leaves rows nothing references; they are inert, and the event that won always points
-- at bytes that exist.
--
-- The body is Tink-encrypted with the row id as AAD, like every other stored quotation of source or
-- tracker text (ADR-014): a specification is the operator's own words about their code.
CREATE TABLE work_item_artifact (
    id           UUID        PRIMARY KEY,
    work_item_id TEXT        NOT NULL REFERENCES work_item(id),
    -- SPEC is what the task must achieve; PLAN is the single-step plan that names it by digest.
    kind         VARCHAR(8)  NOT NULL CHECK (kind IN ('SPEC', 'PLAN')),
    body         BYTEA       NOT NULL,
    -- Over the PLAINTEXT bytes, so the digest a preparation pins is reproducible from what was stored,
    -- not from a ciphertext that changes with every key rotation.
    sha256       CHAR(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reads are always "this item's artifact", never "an artifact by id alone": a decision panel asks about
-- the item it is showing, and the item is what authorizes the read.
CREATE INDEX work_item_artifact_by_item ON work_item_artifact (work_item_id, created_at DESC);
