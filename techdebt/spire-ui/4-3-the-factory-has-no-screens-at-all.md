# Every factory surface is API-only, including the ones an operator must act on

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Large |
| Location | `spire-ui/src` (no runs screen, no harness-credential screen); `spire-orchestrator/.../factory/RunResource`, `.../factory/HarnessCredentialResource` |
| Found during | M1 Task 10 (FR-F12); the plan listed a settings UI and it was deliberately not built |
| Date | 2026-09-03 |

## Issue

The factory has grown four operator-facing surfaces and none of them has a screen. `spire-ui`
contains no reference to `/api/runs`, `factory_run`, or any run status.

- **Runs**: detail and the live transcript are REST and WebSocket only, and there is **no list**
  **endpoint at all** — `RunResource` exposes `GET /{runId}` and `GET /{runId}/transcript` and
  nothing that enumerates runs. A runs screen needs `GET /api/runs` built first.
- **Dispatch resolution** (FR-F10): the attention row instructs the operator to
  `POST {"neverRan": true}` to an API path by hand.
- **The harness credential pool** (FR-F12): adding a key, clearing a rejection and resting a member
  are all `curl`.
- **Cancel and steer** (FR-F6): likewise.

M1's plan named a settings UI for the pool. It was not built, and the reason is consistency rather
than time: adding one screen for credentials while runs themselves have none would make the pool the
only part of the factory an operator can see, which is the wrong first screen. A run list is.

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

- Build the runs list and detail first, then hang the actions off it — cancel, steer, resolve — so
  each control appears next to the run it acts on. The pool is a settings screen and belongs beside
  the LLM and SCM registries.
- Carry over the two lessons the review pipeline's screens already paid for: a new backend status is
  invisible to a compile-time union (`refused` shipped as five green segments under "done"), and a
  class the stylesheet does not define renders as browser default (`styles.contract.test.ts` exists
  for that). Both apply directly to the seven `factory_run` statuses.
- When the screens land, revisit the embedded `POST {...} to /api/...` instructions in
  `RunAttentionRows` and `RunResource.uncertainDetail` — they are correct today precisely because
  there is nowhere else to send anyone.
