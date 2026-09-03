# Neither pool exhaustion state has an automatic producer, so the pool heals only by hand

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../factory/RunCredentialFeedback.java`, `.../factory/HarnessCredentialPool.java` (`markRateLimited`, `markRejected`), `.../factory/HarnessCredentialResource.java` (`/rest`, `/clear-rejection`), `spire-harness/.../FailureCause.java`, `spire-contract/.../RunFailureCause.java` |
| Found during | M1 Task 10 (FR-F12); **corrected** by the Task 10 review round (security, QA) |
| Date | 2026-09-03 |

## Issue

The pool has two exhaustion states and **neither has an automatic producer**.

**This entry previously said the refusal half was "closed and tested". That was wrong**, and both a
security and a QA lens established it by grep rather than by reading. `CREDENTIAL_REJECTED` appears in
main sources only as its enum definition, an unrelated attention-row string for SCM/LLM registry
credential health, and the single consumer in `RunCredentialFeedback`. The harness tier's own
`FailureCause` has no credential value at all; the publisher's vocabulary has none; and
`RunFailureCause.ALIASES` maps nothing onto it. So a provider refusing a key surfaces as
`PROVIDER_ERROR` → `MODEL_UNAVAILABLE`, which the feedback rule deliberately ignores.

The test could not see it because it constructs the wire string by hand, which proves the translation
and says nothing about whether anything crosses the seam.

**Consequence:** a dead key stays in rotation and the pool hands it out again on its next turn. That
is verbatim the state `V52`'s own header calls *"how a pool quietly stops rotating while looking
healthy"* — written in the change that shipped it.

The rate-limit half was never claimed to have a producer, and does not. `MODEL_UNAVAILABLE` covers a
provider outage as well as a rate limit and cannot tell them apart, and inferring one would rest a
good key on every blip — with a small pool, resting every member at once.

`spire-arch`'s `CredentialRefusalHasNoProducerTest` now fails the build when a producer DOES appear,
so this entry cannot go stale in the other direction: whoever wires one up has to come here first.

## Risks

- A refused key is retried once per run for ever. Each attempt starts a container and a dispatch
  before failing, so it is cheap but not free, and the operator sees a run failure rather than a pool
  row telling them which key to replace.
- The self-healing half of the design is inert without an operator, so a deployment whose keys are
  quota-limited behaves as though the pool were single-member.
- The exhaustion attention row only fires once every member is *marked*. With nothing marking, a pool
  of dead keys reports nothing at all and every run simply fails.

## Suggested Solutions

- **Split the wire cause.** `CREDENTIAL_REJECTED` needs a producer in the harness tier: a value on
  `spire-harness`'s `FailureCause`, emitted when the provider returns an auth error, plus an alias.
  `CREDENTIAL_RATE_LIMITED` beside it for a 429, carrying `Retry-After` when the provider states one —
  the SCM adapters already do exactly this with `retryAfterSeconds`. Deliberately not done in M1:
  matching a real provider's error output requires observing it, and inventing the pattern would be
  fabricating behaviour.
- **A second signal is available without any new wire vocabulary, and was missed at design time.**
  The pool records each member's `type`, and `factory_run` records both the member and the cause. A
  provider outage hits *every key of that type*; a rate limit hits *one*. So if a member reports
  `MODEL_UNAVAILABLE` while another member of the same type completed a run in the same window, that
  is not the provider — it is that key. It needs a pool with two or more members of one type, which is
  the pool this feature exists for.
- **When a producer arrives, bound the cascade.** A vendor-side auth outage returning 401 for every
  key would reject the whole pool one run at a time, with no automatic recovery because `rejected_at`
  only clears by hand. Require the provider's *specific* invalid-key signal, not any 401.
- Until then, `POST /api/harness-credentials/{id}/rest` and `DELETE /{id}` are the operator's levers,
  and SMOKE-TEST Mode Q step 5 says so.
