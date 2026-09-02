-- The run worker's own schema (schema-per-service, ADR-011).
CREATE SCHEMA IF NOT EXISTS runworker;

-- The SOLE idempotency mechanism for run dispatch.
--
-- This worker acks a command ON RECEIPT, because an hour-long run cannot ride the review worker's
-- ordered-blocking channel — that exact pairing once stalled a consumer which then re-stalled on
-- every restart and needed a manual offset seek. Acking early moves the redelivery guarantee off
-- Kafka and onto this row, and the write order matters: claim FIRST, then ack. The reverse loses
-- the command entirely on a crash between them.
CREATE TABLE runworker.run_claim (
    run_id     TEXT        NOT NULL,
    slot       TEXT        NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, slot)
);

-- Which run unit a replica currently owns, with the heartbeat that DEFINES an orphan.
--
-- Without owner + heartbeat, discoverOrphans() cannot tell a dead replica's leak from a live
-- replica's healthy hour-long run: reap eagerly and the watchdog kills real work, reap lazily and
-- an eviction leaks forever. Note this row holds no filesystem path — since ADR-039 there is
-- nothing on any worker's disk to point at.
CREATE TABLE runworker.run_lease (
    run_id       TEXT        PRIMARY KEY,
    owner_id     TEXT        NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
