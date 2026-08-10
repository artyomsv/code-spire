# A new backend enum value cannot break the UI build, and degrades into "success"

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-ui/src/api.ts` (`ReviewStatus`, `PrState`, and the other API-mirroring unions), `spire-ui/src/render.tsx` (`STATUS_LABEL`, `miniPipeline`, `outcomeBadge`) |
| Found during | ADR-025 spend caps — found while writing the runbook, not by any test |
| Date | 2026-08-09 |

## Issue

`ReviewStatus` is a **compile-time union over runtime JSON**:

```ts
export type ReviewStatus = 'reviewing' | 'completed' | 'failed' | 'cancelled' | 'superseded' | 'refused' | 'observed';
```

`tsc` checks that literal set. The actual value arrives from the orchestrator's REST and WebSocket
payloads and is never validated against it. So adding a status on the backend **cannot** fail the UI
build, cannot fail `tsc --noEmit`, and cannot fail any existing test — it simply flows through to
whatever the default branch of each consumer does.

ADR-025 added `refused` and this happened in full:

- `STATUS_LABEL` is a `Record<ReviewStatus, string>`, so the lookup answered `undefined` and the badge
  rendered as an **empty pill**. A `Record` is exhaustive over the *declared* union, which gives no
  protection at all against a value that was never declared.
- `miniPipeline` tested each known status in turn and fell through to its terminal case, drawing a
  refused review as **five green `done` segments under a green "done" label** — a review the deployment
  refused to spend on, presented as a successful one.
- `matchesChip` matched no chip, so the row was reachable only under **All** and appeared in no count.

All three shipped green: 1,256 Java tests, 317 vitest tests, `tsc` silent.

The instance is fixed. **The class is not**, and it applies to every union in `api.ts` that mirrors a
backend enum — `ReviewStatus`, `PrState`, the reconciliation verdicts, `ContextItem` kinds. Each has
consumers whose default branch is some specific, non-obviously-wrong behaviour.

## Risks

The failure mode is not a crash or a blank screen, which is what makes it expensive: **the default
branch is frequently the success branch**, because success is the common case and tends to be written
as the fall-through. An operator reading the reviews list would have seen a completed review and never
learned their cap had fired. That is worse than the silence this project has twice paid for (the turn
cap, the archived-review notice), because silence at least looks like nothing happened.

It also defeats the normal safety argument for a union. A reviewer seeing `Record<ReviewStatus, string>`
reasonably concludes the compiler is enforcing exhaustiveness — and it is, over a set that no longer
matches reality. The type reads as a guarantee while providing none.

Recurrence is near-certain: every future status, verdict or kind added on the backend hits the same
seam, and the next one may not be caught by someone happening to write a runbook.

## Suggested Solutions

1. **Validate at the boundary and make the unknown case explicit.** Parse API responses through a small
   decoder (hand-rolled or `zod`) that maps an unrecognised status to an explicit `'unknown'` member of
   the union. Every consumer must then handle `'unknown'`, and the compiler enforces that. This is the
   only option that turns the silent case into a visible one; it is also the one that touches the most
   call sites, so it is worth doing on one union first — `ReviewStatus` — rather than all at once.
2. **Make the fall-throughs explicit rather than implicit.** Replace `miniPipeline`'s trailing `return`
   and similar default branches with an exhaustive `switch` over the union plus a `never` check, so the
   *declared* set is at least enforced and a newly-declared status fails the build until it is handled.
   Cheaper than (1) and complementary, but it does nothing for a status the UI has not declared yet —
   which is precisely the case that occurred here.
3. **A contract test against the backend's own vocabulary.** An exported list checked against `api.ts`
   in CI would fail the moment the two diverge, which catches the root cause (two vocabularies
   drifting) rather than the symptom. **There is no such list to export today**, and that is worth
   knowing before anyone scopes this: `ReviewState.Status` is `{IDLE, REVIEWING, COMPLETED, FAILED,
   CANCELLED}` — the aggregate's five — while the read model writes lower-case strings and has since
   grown `superseded`, `observed` and now `refused`. The projection's vocabulary is strictly larger
   than the aggregate's and is typed as `String` at every write. So this option begins with making the
   read-model status a real type, which is a separate change of its own and one that touches
   `ReviewProjection` (see `techdebt/spire-orchestrator/3-4-…`).

(2) plus (3) is the best value: (2) is an afternoon and prevents half the class, (3) prevents the
other half but only after the read-model vocabulary becomes a type. (1) subsumes both and is the right
end state if the decoder is being added for other reasons anyway.
