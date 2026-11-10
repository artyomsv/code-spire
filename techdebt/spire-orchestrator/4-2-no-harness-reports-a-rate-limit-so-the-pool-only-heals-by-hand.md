# The pool's refusal half heals only for proxied units, and its rate-limit half has no producer

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/.../factory/RunCredentialFeedback.java`, `.../factory/HarnessCredentialPool.java` (`markRateLimited`, `markRejected`), `.../factory/HarnessCredentialResource.java` (`/rest`, `/clear-rejection`), `spire-run-worker/.../ModelProxyResults.java`, `spire-harness/.../FailureCause.java`, `spire-contract/.../RunFailureCause.java` |
| Found during | M1 Task 10 (FR-F12); **corrected** by the Task 10 review round (security, QA); **narrowed** by harness reviews step 3 |
| Date | 2026-09-03, updated 2026-11-08 |

## Issue

The pool has two exhaustion states. Until harness reviews step 3 **neither had an automatic producer**.

**The refusal half now has one, for proxied units only.** A run unit whose model credential sits in a
model proxy (`spire.run.credential-proxy.mode`, default `REVIEWS`: harness review units) reports
`CREDENTIAL_REJECTED` when the model proxy saw the vendor answer **401 on a model call path**
(`/v1/responses`, `/backend-api/codex/responses`) and the agent then failed (`ModelProxyResults`,
`RunLauncher.agentFailure`). `RunResultSaga` hands it to `RunCredentialFeedback`, which marks the member
`factory_run.harness_credential_id` names; `RunResultSagaTest.aReviewRunsRefusedCredentialMarksThePoolMember`
proves the seam. This is the "require the provider's specific signal, not any 401" bound below: a 401
elsewhere (a seat's account check) and every 403 (a Cloudflare challenge, an entitlement answer) mark
nothing.

**What is still open:**

- An **unproxied** unit (factory builds under `REVIEWS`, any unit with the mode `OFF` or
  `spire.run.egress.mode=off`) has no producer. A provider refusing a key there surfaces as the harness's
  own failure (`HARNESS_EXIT_NONZERO` for Codex, exit 120; or `PROVIDER_ERROR` → `MODEL_UNAVAILABLE`),
  which the feedback rule deliberately ignores, so a dead key stays in rotation. `ALL` closes this once
  factory builds are measured behind the model proxy.
- The **rate-limit** half was never claimed to have a producer, and does not. `MODEL_UNAVAILABLE`
  covers a provider outage as well as a rate limit and cannot tell them apart, and inferring one would
  rest a good key on every blip — with a small pool, resting every member at once. The model proxy sees
  a 429 and does not report it yet.
- The proxied producer has no live proof yet: a vendor 401 through the model proxy is proven against
  fakes only.

`spire-arch`'s `CredentialRefusalHasNoProducerTest` guarded the old claim and was deleted with the
producer.

## Risks

- On an unproxied unit a refused key is retried once per run for ever. Each attempt starts a container
  and a dispatch before failing, so it is cheap but not free, and the operator sees a run failure rather
  than a pool row telling them which key to replace.
- The self-healing rate-limit half is inert without an operator, so a deployment whose keys are
  quota-limited behaves as though the pool were single-member.
- A vendor-side auth outage returning 401 on the model call for every key would reject a proxied pool one
  run at a time, with no automatic recovery because `rejected_at` only clears by hand.

## Suggested Solutions

- **Count a 429 in the model proxy's report** and map it to a `CREDENTIAL_RATE_LIMITED` cause, carrying
  `Retry-After` when the vendor states one — the SCM adapters already do this with `retryAfterSeconds`.
- **Proxy factory builds** (`ALL`) once measured, which gives every Codex unit the refusal producer.
- **A second signal is available without any new wire vocabulary.** A provider outage hits *every key of
  that type*; a rate limit hits *one*. If a member reports `MODEL_UNAVAILABLE` while another member of the
  same type completed a run in the same window, that is the key, not the provider.
- **Bound the cascade:** stop marking after N members of one type are rejected within a short window, and
  raise an attention row instead.
- Until then, `POST /api/harness-credentials/{id}/rest` and `DELETE /{id}` are the operator's levers,
  and SMOKE-TEST Mode Q step 5 says so.
