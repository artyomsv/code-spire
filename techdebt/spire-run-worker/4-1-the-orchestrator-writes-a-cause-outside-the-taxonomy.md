# Three causes reach the read model without passing the taxonomy

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/FactoryRunProjection.java` (`dispatchFailed` writes `DISPATCH_FAILED` directly); `RunFailureCause.DISPATCH_FAILED`, `DISPATCH_UNCERTAIN`, `CANCELLED` |
| Found during | M1 Task 1 four-lens review (security, QA) |
| Date | 2026-09-02 |

## Issue

Two related gaps, both about which side of the wire a cause may come from.

The orchestrator is a **fourth producer** the cause scan does not cover. `dispatchFailed` writes
`DISPATCH_FAILED` into `failure_cause` as a constant, never going through `RunFailureCause.of`. It is
self-consistent today because the same constant backs the re-arm query, and the scan's javadoc says
it looks "across the repository" while walking two modules.

In the other direction, three values name facts only the orchestrator can know — `DISPATCH_FAILED`,
`DISPATCH_UNCERTAIN` and `CANCELLED` — and `FactoryRunProjection.failed` accepts all three from
`cs.run-results`, which is the worker's channel. A worker-emitted `DISPATCH_FAILED` re-opens the
re-arm window that `queued` guards.

Harmless today: the claim store drops the duplicate, and the bus is inside the ADR-014 trust
boundary. It stops being harmless when M1's cancel and idempotent-dispatch work give those values
real producers and real consequences.

## Risks

- A future orchestrator-side cause drifts from the closed set with no guard, which is the whole
  failure the scan exists to prevent, one module over.
- A run result asserting a control-plane fact re-arms a dispatch the control plane did not fail.

## Suggested Solutions

- Extend the scan's module list to the orchestrator, and route `dispatchFailed` through
  `RunFailureCause.DISPATCH_FAILED.name()` so there is one spelling.
- On arrival from `cs.run-results`, map the three control-plane causes to `UNCLASSIFIED` with the
  producer's word kept in the detail, exactly as an unrecognised value is handled. Best done in
  Task 9, which is where `DISPATCH_UNCERTAIN` gains its producer.
