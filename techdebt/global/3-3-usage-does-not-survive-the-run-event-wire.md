# `UsageReport` cannot round-trip the run-event wire

**Criticality:** 3 (Medium) — **Complexity:** 2 (Low)

## What

`RunEvent.Usage` carries a `UsageReport`, and `RunEvent` is planned to go onto `cs.run-events`
as `Emitter<RunEvent>` (M0 plan, Task 8). `UsageReport` is a plain final class with no default
constructor, no `@JsonCreator`, and no getter-shaped accessors: `isUnknown()` reads as the
property `unknown`, while `asMap()` and `tokens(TokenBucket)` are not bean getters at all.

So a report serializes to `{"unknown":false}` — **every count silently dropped** — and does not
deserialize at all.

## Why it matters

It is the same failure this whole module was built to prevent, arriving through the wire instead
of through the arithmetic: a run whose usage was measured correctly arrives at the other end
carrying no counts, and the only honest reading of a report with no counts is UNKNOWN. The
telemetry RUN-TOPOLOGY §10 exists for would be empty on every run, and nothing would fail.

## The fix

Keep `UsageReport` off the wire entirely. Give `RunEvent.Usage` a nullable
`Map<TokenBucket, Long>` component — where **null is the unknown case**, which is exactly the
distinction the type protects — and rebuild the report on read.

Not done here because `RunEvent` does not yet cross a wire; Task 7 defines the run wire contract
and is where the shape must be settled. The contract-snapshot gate will not catch it either:
`ContractSchemaSnapshotTest` does not recurse into nested wire types
(`techdebt/spire-contract/3-2-...`), and `UsageReport` is nested inside `RunEvent.Usage`.

## Discovered

2026-09-01, during the Task 2 four-lens review of the M0 walking skeleton.
