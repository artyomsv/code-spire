# PrRegistry is in-memory; a restart mid-review degrades redelivered commands

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/PrRegistry.java:23` |
| Found during | Phase 1 code review (code-reviewer finding L3) |
| Date | 2026-07-03 |

## Issue

Worker result events don't carry `RepoRef`, so `ResultSaga` reconstructs it from the in-memory
`PrRegistry` (populated in `DiffWorker.fetchDiff`). After an orchestrator restart mid-review, a
redelivered result event resolves to the `unknown/unknown` fallback and the follow-up command
targets a nonexistent repo (404) instead of resuming cleanly.

## Risks

Confusing failure ("Bitbucket API GET /repositories/unknown/unknown/... failed with HTTP 404")
after any restart with in-flight reviews. Masked today by the single-process P1 topology and the
stale-run pre-check, but it will bite during the P1+ service split if the shim survives.

## Suggested Solutions

1. Carry `RepoRef` on the worker result events (`DiffFetched`, `ContextAssembled`,
   `ReviewGenerated`, ...) — the planned fix; the shim is documented to die at the service split.
2. Alternatively derive repo/prId by parsing the `reviewId` (it embeds `workspace/slug#prId`).
3. Either way, delete `PrRegistry`.
