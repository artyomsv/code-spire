# Three factory surfaces still have no screen, and each is an operator action

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Large |
| Location | `spire-ui/src` (no harness-credential screen, no dispatch-resolution or cancel control); `spire-orchestrator/.../factory/RunResource`, `.../factory/HarnessCredentialResource` |
| Found during | M1 Task 10 (FR-F12); the plan listed a settings UI and it was deliberately not built. **Narrowed 2026-09-04**, when M2 Task 9 built the runs list this entry said had to come first |
| Date | 2026-09-03 (updated 2026-09-04) |

## Issue

The factory has four operator-facing surfaces. M2 Task 9 built the first; three still have none.

**Fixed on 2026-09-04 — the runs list.** This entry originally read "the factory has grown four
operator-facing surfaces and none of them has a screen", and argued that a runs list had to come
first because "`RunResource` exposes `GET /{runId}` and `GET /{runId}/transcript` and nothing that
enumerates runs". M2 Task 8 built `GET /api/runs` and Task 9 built `Runs.tsx` on it, so all three
of those claims are now false and the entry is narrowed rather than closed. The argument it made
was the right one and it is recorded because the order it recommended is the order that happened.

What remains, each an action an operator takes with `curl`:

- **Dispatch resolution** (FR-F10): the attention row instructs the operator to
  `POST {"neverRan": true}` to an API path by hand.
- **The harness credential pool** (FR-F12): adding a key, clearing a rejection and resting a member
  are all `curl`.
- **Cancel and steer** (FR-F6): likewise.

M1's plan named a settings UI for the pool. It was not built, and the reason is consistency rather
than time: adding one screen for credentials while runs themselves have none would make the pool the
only part of the factory an operator can see, which is the wrong first screen. A run list is — and
now exists, so that objection is spent and the pool screen is next rather than blocked.

**The runs screen is READ-ONLY**, which is why every item above is still open: it lists, filters and
links, and offers no control that changes a run.

The attention panel is the one place this does surface — its rows render generically from
`code: string`, so every factory row appears correctly with no UI change. That is why this is Low
rather than Medium: an operator IS told when something needs them, in the same bell as everything
else. What they cannot do is act without a terminal.

## Risks

- Every factory action is a hand-written HTTP request, so a typo in a run id is an operator's problem
  and the endpoints' error messages are the entire user interface.
- The instructions embedded in attention messages and stored failure details are load-bearing for
  that reason, and they will drift from the API the moment a screen is added and nobody removes them.

## Suggested Solutions

- The runs list exists; **hang the actions off it** — cancel, steer, resolve — so each control
  appears next to the run it acts on. The pool is a settings screen and belongs beside the LLM and
  SCM registries.
- Carry over the two lessons the review pipeline's screens already paid for: a new backend status is
  invisible to a compile-time union (`refused` shipped as five green segments under "done"), and a
  class the stylesheet does not define renders as browser default (`styles.contract.test.ts` exists
  for that). Both apply directly to the nine `factory_run` statuses. `Runs.tsx` is the worked
  example: its `RunStatus` union lists all nine AND every reader defaults an unlisted value to
  unknown, never to green and never to busy.
- When the screens land, revisit the embedded `POST {...} to /api/...` instructions in
  `RunAttentionRows` and `RunResource.uncertainDetail` — they are correct today precisely because
  there is nowhere else to send anyone.
