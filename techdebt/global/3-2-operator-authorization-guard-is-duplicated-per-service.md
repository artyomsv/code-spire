# The operator-authorization guard is copied into all three services

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-gateway/.../security/OperatorAuthorization.java`, `spire-review-worker/.../security/OperatorAuthorization.java`, `spire-orchestrator/.../security/OperatorAuthorization.java` |
| Found during | D10 slices 1–3 (operator authentication) |
| Date | 2026-08-03 |

## Issue

Each service carries its own copy of the same class: an `AuthorizationController` reading
`spire.security.auth-enabled`, plus a startup observer that **refuses to boot** when authentication is
disabled outside `%dev`/`%test`. The three differ only in the refusal message, which names what that
particular service exposes.

Extraction into a shared module was considered at the third copy and deliberately declined. The
duplicated *logic* is one boolean expression (`isPermitted`); the duplicated *ceremony* is CDI
annotations. A shared module would have to be Quarkus-aware — so it needs jandex indexing to have its
beans discovered — and under ADR-021 a new module brings its own `LICENSE` plus a `LICENSING.md`
entry. That is a lot of machinery around one expression.

**What was chosen instead was a drift check, and it has not been written.** That is the actual debt:
the reasoning assumed a guard that does not yet exist.

## Risks

Security-relevant, which is why this is Medium rather than Low despite being small.

The failure mode is a *partial* change: someone tightens or fixes the fail-fast in one service and
misses the other two. Nothing fails — each service still compiles, still starts, still passes its own
tests — and two services silently retain the weaker rule. The specific consequence is a service that
boots unauthenticated in production while its siblings refuse to, which is precisely the outcome D10
exists to prevent, and it would be invisible until someone read all three files side by side.

Today the three are identical, so the risk is entirely about future edits.

## Suggested Solutions

1. **Write the `spire-arch` check that was assumed** (the intended fix). The repository already has
   the idiom: `RedirectHandlingHasOneHomeTest` fails the build when a permitted duplicate drifts, and
   `CoreIsProviderNeutralTest` scans source text for the same class of problem. Assert that every
   service module defines the guard, and that their `isPermitted` rules are textually identical — so
   a partial change fails the build rather than shipping.
2. Extract a shared module after all. Rejected above for cost, but it becomes the better trade if the
   class grows beyond a single rule — at which point "identical text" stops being a fair assertion.
3. Leave it. Defensible only while the three stay identical, which is exactly what nothing currently
   enforces.
