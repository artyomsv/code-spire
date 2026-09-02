# A lease with no unit is reclaimed by nothing, and its reader is dead code

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/OrphanWatchdog.java` (the sweep iterates `discoverUnits()` only), `.../WorkspaceLeases.java` (`staleLeases`, called from no production code) |
| Found during | M1 Task 6 four-lens review, round 1 (security-officer and rules-compliance, independently) |
| Date | 2026-09-02 |

## Issue

`techdebt/spire-run-worker/2-4` was deleted as closed by the orphan watchdog. One arm of it is not
closed, and this entry restores that arm rather than the whole thing.

The watchdog is driven entirely by the runtime's unit listing and consults the lease per discovered
unit. So it reclaims a **unit with no lease**. It cannot see the reverse — a **lease with no unit** —
because there is nothing on the daemon to discover.

That state is not hypothetical. It is the window the take-before-create ordering opens *on purpose*:
`V2__run_lease_unit.sql` says the column is nullable precisely because a crash between taking the
lease and creating the sandbox must leave a lease naming no unit, described there as *"a row the
watchdog can reconcile against the daemon"*. Nothing reconciles in that direction.

**And the reader written for it is dead code.** `WorkspaceLeases.staleLeases(Duration)` was added by
Task 5 with a javadoc that names its intended caller — *"Empty on a read fault, which fails CLOSED
for a watchdog"* — and has no production caller. The next maintainer will reasonably read it as the
live path.

## Risks

- A replica evicted between `leases.take` and `runtime.create` leaves `factory_run` in `queued`
  for ever: the redelivered command is refused by the claim, no terminal result is emitted, and no
  attention row fires. That is `2-4`'s first Risks bullet verbatim, on the arm that survived it.
- The lease row is never released either, so it accumulates and ages, and every future sweep that
  reads the table pays for it.
- `staleLeases` looks like the watchdog's mechanism and is not, which is the class of misleading
  name this milestone renamed `discoverOrphans` to remove.

## Suggested Solutions

- A second leg on the sweep: for each `staleLeases()` row whose run is absent from
  `discoverUnits()`, report `SANDBOX_LOST` under the same `reap` claim and release the lease. That
  makes `staleLeases` the live path its javadoc already claims, and needs no new storage.
- The claim-without-lease case — an eviction between `claims.claim` and `leases.take` — leaves
  nothing in either table and needs an orchestrator-side stuck-run attention row instead. That is a
  different mechanism and should not be smuggled into this one.
- Whichever is built, delete or re-document `staleLeases` in the same change: a method whose javadoc
  names a caller it does not have is worse than one with no javadoc at all.
