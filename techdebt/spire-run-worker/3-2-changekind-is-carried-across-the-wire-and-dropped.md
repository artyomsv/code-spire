# `ChangeKind` is carried across the publisher's wire specifically to be reported, and the worker drops it

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-run-worker/.../PublisherOutcome.java:94`; `spire-publisher/.../OutcomeWriter.java:80`; `spire-workspace/.../PushDecision.java:10` |
| Found during | PR #96 whole-PR review (code-quality I7) |
| Date | 2026-09-03 |

## Issue

Three modules argue for this field, and the fourth discards it.

`OutcomeWriter`'s javadoc: *"every refused path WITH what happened to it — 'ci.yml was blocked' does
not tell an operator whether the factory edited that workflow or deleted it"*, and `describe()` duly
emits `{"path": …, "kind": …}`. `PushDecision` makes the same argument for typing `blocked` as
`List<ChangedPath>` rather than strings. `PushGate`'s class javadoc repeats it.

Then `PublisherOutcome`:

```java
String path = entry.isObject() ? entry.path("path").asText(null) : entry.asText(null);
```

The kind is read from the JSON and thrown away. `RunResult.RunFinished.changedPaths`/`blockedPaths`
are `List<String>`, `FactoryRunProjection.finished` stores `String.join("\n", …)`, and
`RunView.blockedPaths` is `List<String>`. **No operator ever sees the kind.**

Each module's own review saw only its own half.

## Risks

Low operationally; the cost is that the current state reads to a future author as though the
information is available, so the next person to build a refusal screen will look for a field that
never arrives.

## Suggested Solutions

Two honest options, and the current state is neither:

1. **Carry it.** `List<ChangedPath>`-shaped entries on the wire record — a `spire-contract` change
   that needs the snapshot gate re-baselined. This is what FR-F28's operator story actually asked for.
2. **Delete the claim** from all three javadocs and from `PushDecision`'s reason for holding
   `ChangedPath`. Cheap and honest.
