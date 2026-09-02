# The run-event stream was accumulated without bounds

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-harness/src/main/java/dev/codespire/harness/RunEventSummary.java` (`List<RunEvent> events`), `spire-run-worker/.../RunLauncher.java` (now folds through `RunEventFold`; the SPI still carries the list) |
| Found during | PR #95 four-lens review of Task 2, then Task 8 |
| Date | 2026-09-01 |

## Issue

`RunEventSummary` holds every event a run emits so that `HarnessAdapter.classify` and
`HarnessAdapter.usage` can read them. The worker is stateless and shared by every concurrent run
(RUN-TOPOLOGY §7), so worker heap became a function of model chattiness — and the agent writes to
the same stdout the harness does, at `danger-full-access`, so the volume is attacker-influenced. A
single very long line, or an event flood, was a denial of service on the shared worker rather than
on the run that caused it. `CodexAdapter` clips each field to 2000 characters, so one event is
bounded; the number of events and the length of a line before it is parsed were not.

The worker side is now folded (`RunEventFold`: only usage events kept, bounded, plus the
`sawAnyOutput` flag). What remains is the SPI's shape: `RunEventSummary.of(List)` still exists, and
`sawAnyOutput` still has two meanings depending on which constructor built it — `of()` derives it
from the events, the canonical constructor lets a caller mean "bytes hit stdout" — so the same
failing run can classify as `NO_MODEL_RESPONSE` or `HARNESS_EXIT_NONZERO` by construction path, and
FR-F9 makes that cause durable.

## Risks

- A second arm written against the SPI's list-shaped summary reintroduces the accumulation the
  worker just removed.

## Suggested Solutions

- Replace the list in the SPI with the folded shape (`eventCount`, `sawAnyOutput`, `lastUsage`) and
  a contracted maximum line length, truncating and marking rather than throwing; delete the
  two-meaning constructor.
