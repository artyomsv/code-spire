# A worker death between claim and result strands the run permanently

| Field | Value |
|-------|-------|
| Criticality | High |
| Complexity | Large |
| Location | `spire-run-worker/src/main/java/dev/codespire/runworker/RunDispatcher.java` (claim, then launch), `spire-run-worker/src/main/resources/db/migration/V1__run_claim.sql` (`runworker.run_lease`, written and read by nothing), `spire-runtime-docker/.../DockerRunRuntime.java` (`discoverOrphans`, called from no production code) |
| Found during | PR #95 four-lens review, round 1 (code-reviewer and security-officer, worker side) |
| Date | 2026-09-02 |

## Update — 2026-09-02, M1 Task 5 (the lease)

**Half of this is now closed.** `runworker.run_lease` is written and read: the lease is taken
BEFORE the unit is created, its heartbeat advances on a timer while the run is alive, it records
the sandbox id as soon as one exists, and it is released on every terminal path EXCEPT a preserved
unit — which keeps its lease on purpose, because a preserved unit is exactly what a watchdog must
find.

So the definition of an orphan that V1's comment describes at length — owner plus heartbeat —
finally has data behind it. `WorkspaceLeases.staleLeases` answers the question, deliberately
without filtering by owner: a lease THIS replica holds and has stopped heartbeating is exactly as
stale as a dead replica's, and filtering would make a hung replica invisible to its own watchdog.

`RunStarted` also carries the real unit id now rather than the run id twice, which closes
`techdebt/spire-run-worker/4-1-…` — the lease is where that identity finally had somewhere to live,
so the two were one change.

**What remains is acting on it**, which is Task 6. Nothing yet reads `staleLeases`, nothing calls
`discoverOrphans`, and a replica evicted mid-run still leaves a claim with no result. The lease now
says which runs those are; the watchdog is what does something about them.

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
