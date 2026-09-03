# Nothing marks a credential rate-limited, so half the pool's recovery is manual

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-orchestrator/.../factory/HarnessCredentialPool.java` (`markRateLimited`), `.../factory/RunCredentialFeedback.java`, `.../factory/HarnessCredentialResource.java` (`/rest`), `spire-contract/.../RunFailureCause` |
| Found during | M1 Task 10 (FR-F12), recorded at implementation rather than at review |
| Date | 2026-09-03 |

## Issue

The pool has two exhaustion states and only one of them has an automatic producer.

A refusal does: a run failing with `CREDENTIAL_REJECTED` marks the member it was dispatched with, and
the pool rotates past it until an operator replaces the key. That loop is closed and tested.

A rate limit does not. The nearest cause on the wire is `MODEL_UNAVAILABLE`, which covers a provider
outage as well as a rate limit and cannot tell them apart. `RunCredentialFeedback` deliberately marks
nothing on it, and that restraint is the right call for now: treating an outage as exhaustion would
rest a perfectly good key on every blip, and with a small pool one outage would rest every member at
once — turning a transient fault into a refusal that quotes a recovery time nobody can rely on.

So `markRateLimited` exists, is tested, and is reachable only from `POST
/api/harness-credentials/{id}/rest`. An operator watching a provider console can rest a member by
hand; nothing does it for them. This is the same shape as the steer capability no shipped harness
declares — stated here rather than left for someone to discover from a code path nothing reaches.

The practical consequence is bounded: a rate-limited key is not marked, so the pool keeps handing it
out and each run fails against it until the limit lifts. Those runs are cheap (they fail at the first
model call) but not free, and the operator sees a run failure rather than a pool row.

## Risks

- A rate-limited key is retried once per run until the vendor's window passes, wasting a dispatch and
  a container start each time.
- The self-healing half of the pool's design is inert without an operator, so a deployment with one
  quota-limited key behaves as though the pool were single-member.

## Suggested Solutions

- Split the wire cause: a `CREDENTIAL_RATE_LIMITED` beside `CREDENTIAL_REJECTED`, produced by the
  harness when the provider returns a 429 (and carrying `Retry-After` when the provider states one —
  the SCM adapters already do exactly this with `retryAfterSeconds`). That is the honest fix and it
  needs a change in the harness tier, which is why it is not in M1.
- Until then, do NOT infer a rate limit from `MODEL_UNAVAILABLE`. The two are genuinely
  indistinguishable in it, and guessing costs a healthy key.
- The default rest window (`spire.run.credential-rate-limit-default-seconds`, 900s) only applies when
  a producer supplies no time. Once a producer exists and passes `Retry-After`, that default should
  become the fallback it was written as rather than the only value ever used.
