# A rejected code-provider credential never reaches an attention row

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-context-code/src/main/java/dev/codespire/context/code/CodeContextApiException.java` (`isUnauthorized`), `CodeContextProvider.java` (`Fetcher.read`), `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ContextWorker.java` |
| Found during | Task 15 — repository knowledge base rung 1, final verification pass (I4) |
| Date | 2026-08-26 |

## Issue

`CodeContextApiException.isUnauthorized()` is implemented correctly — deliberately 401-only, matching
`ScmApiException.isUnauthorized()` — but it has no caller anywhere in production code. A grep across
`spire-context-code` and `spire-review-worker/src/main` finds only the declaration.

`CodeContextProvider.Fetcher.read` catches `RuntimeException` uniformly:

```java
} catch (RuntimeException e) {
    // A real error (5xx, rate limit) — skip this one path and let every other file still
    // contribute; ContribStatus.ERROR fires only when the whole contribution ends up empty.
    hadError = true;
    return null;
}
```

A `CodeContextApiException` with status 401 is caught here exactly like a 500 or a rate limit: the
path is reported absent, `hadError` is set, and (per I2's deadline handling) the contribution degrades
to `ERROR` only if nothing else resolved. Nothing distinguishes "the credential was rejected" from
"the host had a bad moment."

The design spec this module was built from
(`docs/superpowers/specs/2026-08-25-repository-knowledge-base-design.md` §8.1) originally stated "File
fetch 401/403 → Credential rejected → attention row, via the existing `isUnauthorized()` path" as
delivered behavior. It was not: no code path ever asks `isUnauthorized()` a question, and nothing
carries a credential-rejection outcome out of `CodeContextProvider` to anywhere that could raise an
attention row. The spec has been corrected to state this plainly (task 15, final review response);
this entry tracks closing the actual gap.

## Risks

An operator whose code-provider token is revoked or expires gets **no signal** that anything changed.
Every subsequent review's code context silently goes empty; the only observable trace is `CODE`
appearing in `ContextAssembled.missingSources` on individual reviews — which reads identically to the
ordinary, legitimate case of a diff with no resolvable symbols (see the related M2 entry on this same
task: `missingSources` cannot distinguish "nothing to look up" from "found something to look up and
failed"). The registry's own Check button still works if the operator thinks to use it, but nothing
prompts them to. This is the same class of gap the attention panel exists to close for every other
credential (SCM, LLM, other context providers) — see `docs/superpowers/plans/2026-07-27-attention-panel.md`
and the panel's own design note that credential health "rides on work already happening" for the SCM
and context registries. The code provider is the one context provider where that promise currently does
not hold for the automatic (non-Check-button) path.

Not urgent: the 20s aggregation budget already bounds the blast radius to "this one source went dark,"
never a stuck or hung review, and the review itself still completes using whatever other context
resolved. This is a completeness/observability gap, not a reliability one.

## Suggested Solutions

1. **Carry the outcome out of the contribution.** `ContextContribution` (or a sibling type reachable
   from `ContextResolutionSource.Resolution`, which the code provider already implements to expose its
   `ContextResolutionCounts`) would need a way to say "the credential was rejected" distinctly from
   `ContribStatus.ERROR`. `ContextWorker` would then need to translate that into whatever mechanism
   the other registries use to raise `CREDENTIAL_REJECTED` (see `ReviewFailed.credentialRejected` for
   the SCM precedent) — the code registry does not currently have an equivalent event to piggyback on,
   since `GatherContext`/`ContextAssembled` carry no per-provider failure-reason field today. This is
   the natural fix and the one the original spec assumed, but it is a wire-shape change spanning
   `spire-contract`, `spire-context-code`, and `spire-review-worker` — hence Medium complexity, not
   Small.
2. **Narrower interim step:** have `Fetcher.read` at least log (structured, no secret) when a caught
   exception is a 401, so the fact is visible in the worker's own logs even before it reaches the
   attention panel — cheap, but does not close the actual gap the spec promised.
3. Leave as is. Defensible short-term because the failure degrades gracefully (a review still
   completes, just without code context), but every review after the rejection silently loses a
   feature the operator paid to configure, for as long as nobody happens to open Settings → Context.
