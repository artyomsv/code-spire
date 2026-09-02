-- The lease learns which sandbox it is a lease ON.
--
-- V1 created runworker.run_lease with run_id, owner_id and heartbeat_at, under a comment explaining
-- at length why owner plus heartbeat is what DEFINES an orphan -- and then nothing wrote or read it.
-- Task 5 makes it real. This migration adds the one column that description was missing.
--
-- The plan for this task called for a NEW table named workspace_lease. That would have left two
-- answers to one question, which is the shape the claim store's own debt entry already warns about,
-- so the table that exists is the table that gets used.
--
-- NULLABLE, and that is the whole ordering guarantee rather than laziness. The lease is taken BEFORE
-- the run unit is created, because a crash between the two must leave a lease with no unit -- a row
-- the watchdog can reconcile against the daemon -- and never a unit with no lease, which is a
-- sandbox holding a credential that nothing knows exists. So there is a real window in which this
-- column is legitimately empty, and a NOT NULL would have forced the ordering the other way round.
ALTER TABLE runworker.run_lease ADD COLUMN IF NOT EXISTS unit_id TEXT;

-- The watchdog's read is "whose leases are stale", so it scans by heartbeat rather than by run.
-- Small table by construction -- one row per IN-FLIGHT run -- but the sweep runs on a timer forever
-- and a sequential scan per tick is a cost with no upper bound on a busy deployment.
CREATE INDEX IF NOT EXISTS run_lease_by_heartbeat ON runworker.run_lease (heartbeat_at);
