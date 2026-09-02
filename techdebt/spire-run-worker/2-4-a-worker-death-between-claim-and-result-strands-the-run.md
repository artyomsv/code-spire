# A worker death between claim and result strands the run permanently

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Large |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunDispatcher.java` (claim, then launch), `spire-run-worker/src/main/resources/db/migration/V1__run_claim.sql` (`runworker.run_lease`, written and read by nothing), `spire-runtime-docker/.../DockerRunRuntime.java` (`discoverOrphans`, called from no production code) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer and security-officer, worker side) |
| Date | 2026-09-02 |

## Issue

The claim on `run_claim` is taken, `RunStarted` is emitted, and the launcher then blocks for the
run's whole duration. If the replica is evicted at any point after the claim, the redelivered
command is refused by the claim as a redelivery and dropped with an info log. No terminal result is
ever emitted, nothing releases a claim, and the run's containers and volumes stay behind on the
daemon holding a 4 GB reservation, two CPUs and a live model credential. `run_lease` — owner plus
heartbeat, whose comment in V1 explains at length why those two fields are what define an orphan —
exists for exactly this and is unused; `discoverOrphans` is exercised only by an integration test.

The plan schedules the watchdog for M1 (FR-F7, FR-F12). It is recorded here so the gap is not
mistaken for dead code or for a guarantee the claim store gives.

## Risks

- A single eviction during a run leaves the orchestrator's `factory_run` row in `running` forever
  with no attention row (the `REVIEW_STUCK` proxy does not cover runs) and a paid container idle.
- The deliberate preservation paths have the same exposure without an eviction: a unit whose
  salvage failed, or whose init failed, is kept for inspection by design — and its publisher and
  init containers keep the machine account's write token in the daemon's container config
  (`docker inspect`) with no expiry, until an operator destroys it by hand.
- The same shape produced a poison-pill incident once already in this project: a record redelivered
  on every restart and refused every time.

## Suggested Solutions

- Take the lease with the claim and heartbeat it during the run; on startup, a replica whose lease is
  stale reattaches to the unit found by `discoverOrphans` or emits a terminal `RunFailed`.
- Minimum viable version: a startup sweep emitting `RunFailed("WORKER_LOST", …)` for every claim with
  no lease and no result, and destroying the unit it finds by label.
- Until then, a unit that leaked is findable with `docker ps -a --filter label=dev.codespire.runId`
  (SMOKE-TEST Mode Q records the command).
