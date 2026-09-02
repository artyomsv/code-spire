-- FR-F5 / ADR-034: the run transcript, the second of the two event tiers.
--
-- A separate table from event_log, and that separation is the decision. One observed agent run
-- emitted 858 events where a review produces a handful, so writing this into the aggregate's
-- durable log would multiply event-store volume by three orders of magnitude, encrypt every line of
-- it, and make replay useless. Nothing derives state from these rows and they are not replayable:
-- they exist for the live tail, for debugging, and for a transcript an operator can read.
--
-- Bounded twice, on purpose, because the two bounds fail differently. The worker caps events per
-- run before they are ever sent (a runaway agent cannot flood the bus), and the sweep below caps
-- how long any of it is kept (a busy deployment cannot grow this table without limit). Neither
-- subsumes the other: the first is about one run, the second about all of them over time.
--
-- payload is ENCRYPTED (ADR-011). A tool result quotes source, a thinking line quotes whatever the
-- agent was reading, and the run's output is model text about a private repository. The columns
-- that are NOT encrypted are the ones a query needs -- run id, sequence, kind, timestamp, the error
-- flag -- which is the same split review_finding already makes between its location columns and its
-- message.

CREATE TABLE run_event (
    run_id      VARCHAR(512) NOT NULL,
    seq         BIGINT       NOT NULL CHECK (seq >= 1),
    at          TIMESTAMPTZ  NOT NULL,
    kind        VARCHAR(32)  NOT NULL,
    is_error    BOOLEAN      NOT NULL DEFAULT FALSE,
    payload     TEXT         NOT NULL,
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The run and its place in the stream. Redelivery is therefore free: the same event arriving
    -- twice conflicts rather than duplicating a transcript line, and no reader has to de-duplicate.
    PRIMARY KEY (run_id, seq)
);

-- No index on (run_id, seq): the PRIMARY KEY above already builds a unique btree on exactly those
-- columns in that order, and it serves both reads. A second identical one would be pure write
-- amplification on what this same file calls the largest table in the schema.

-- The sweep deletes by age across every run, so it needs its own index: without it the retention
-- job degrades into a full scan of the largest table in the schema, which is exactly when it is
-- least affordable to run.
CREATE INDEX run_event_by_age ON run_event (recorded_at);
