-- The lease learns which sandbox it is a lease ON, and when that sandbox was deliberately kept.
--
-- V1 created runworker.run_lease with run_id, owner_id and heartbeat_at, under a comment explaining
-- at length why owner plus heartbeat is what DEFINES an orphan -- and then nothing wrote or read it.
-- Task 5 makes it real. This migration adds the two columns that description was missing.
--
-- The plan for this task called for a NEW table named workspace_lease. That would have left two
-- answers to one question, which is the shape the claim store's own debt entry already warns about,
-- so the table that exists is the table that gets used.

-- Which sandbox. NULLABLE, and that is the whole ordering guarantee rather than laziness: the lease
-- is taken BEFORE the run unit is created, because a crash between the two must leave a lease with
-- no unit -- a row the watchdog can reconcile against the daemon -- and never a unit with no lease,
-- which is a sandbox holding a credential that nothing knows exists. So there is a real window in
-- which this column is legitimately empty, and NOT NULL would have forced the ordering the other
-- way round.
ALTER TABLE runworker.run_lease ADD COLUMN unit_id TEXT;

-- When the unit was deliberately kept rather than destroyed, and why that needs its own column.
--
-- The first version of this change simply skipped the DELETE for a preserved unit. That is not
-- enough, and the review caught it: the heartbeat sweep updates every lease its owner holds, so a
-- preserved lease was refreshed every thirty seconds for the life of the replica and could NEVER go
-- stale. A watchdog defined on staleness would have found it only after a restart -- which is the
-- one case where the lease was reapable anyway, since the owner id is fresh per process. The
-- preservation was invisible exactly as it had been before the lease existed at all.
--
-- Stamping it makes preservation a STATE rather than the absence of an event. The heartbeat skips a
-- stamped row, so it ages naturally; and a watchdog can treat a stamped row as actionable at once,
-- which is more correct than waiting for staleness because there is nothing left to wait for.
--
-- The owner id is deliberately left intact. Rewriting it to a sentinel would also stop the sweep
-- matching, and was considered and rejected: the column exists to answer "whose was it", which is
-- the first question an operator asks about a container they did not expect to find.
ALTER TABLE runworker.run_lease ADD COLUMN preserved_at TIMESTAMPTZ;

-- The watchdog's read is "which leases are stale or preserved", so it scans by heartbeat rather than
-- by run. The table holds one row per in-flight run PLUS one per preserved unit -- the second group
-- persists until something reclaims it, which is the watchdog's job -- so it is small but not
-- self-limiting, and a sequential scan on every tick forever is a cost with no upper bound.
CREATE INDEX run_lease_by_heartbeat ON runworker.run_lease (heartbeat_at);
