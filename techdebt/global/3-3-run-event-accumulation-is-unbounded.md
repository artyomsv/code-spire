# The run-event stream is accumulated without bounds

**Criticality:** 3 (Medium) — **Complexity:** 3 (Medium)

## What

`RunEventSummary` holds `List<RunEvent> events`, and the worker will accumulate every event a
run emits in order to hand them to `HarnessAdapter.classify` and `HarnessAdapter.usage`. Nothing
caps the list, the per-event text, or the length of one NDJSON line.

## Why it matters

Three separate reviewers raised this independently, from different directions:

- The worker is **stateless and shared by every concurrent run** (RUN-TOPOLOGY §7). Worker heap
  therefore becomes a function of model chattiness, and one very talkative run degrades every
  other run on the same replica.
- The agent writes to the **same stdout the harness does** (RUN-TOPOLOGY §313), and it runs at
  `danger-full-access`. So the volume is not merely "however verbose the model was" — it is
  attacker-influenced. A single 2 GB line, or an event flood, is a denial of service on the
  shared worker rather than on the run that caused it.
- `usage()` retains the whole list purely to find the **last** usage event, which does not need
  the list at all.

`CodexAdapter` already clips each field to 2000 characters, so a single event is bounded. What is
not bounded is the number of events, and the length of the line before it is parsed — the line is
read whole and Jackson parses it whole, both before any clip applies.

## The fix, and why it is not done here

Fold instead of accumulate:

```java
record RunEventSummary(int eventCount, boolean sawAnyOutput, UsageReport lastUsage) {
    RunEventSummary plus(RunEvent event) { ... }
}
```

plus a contracted maximum line length and event count in the SPI, truncating and marking rather
than throwing.

This also dissolves a second problem in the same type: `sawAnyOutput` currently has two meanings
depending on which constructor built it — `of()` derives it from the events, while the canonical
constructor lets a caller mean "bytes hit stdout". The same failing run then classifies as
`NO_MODEL_RESPONSE` or `HARNESS_EXIT_NONZERO` depending on the construction path, and FR-F9 makes
that cause durable.

**Deferred to Task 8 deliberately**, because that is where the worker actually accumulates the
stream, and the right shape for the fold is decided with its consumer in hand rather than guessed
one module away. Doing it now would mean designing the accumulator against no caller.

## Discovered

2026-09-01, during the Task 2 four-lens review of the M0 walking skeleton.
