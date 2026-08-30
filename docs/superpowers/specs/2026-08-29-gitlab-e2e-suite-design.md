# GitLab end-to-end suite — design

**Date:** 2026-08-29
**Status:** design, not implemented. Revised twice — after an adversarial review round, then to
settle the two questions it left open (§13).
**Relationship to existing work:** complements `docs/SMOKE-TEST.md` Mode G, which stays the manual
runbook for GitHub and Bitbucket.

## 1. Problem

Everything automated below the SCM boundary runs against WireMock. A WireMock stub is *our belief
about the API*, authored by the same person holding the same wrong model of it. The defect log in
`CLAUDE.md` is a list of places that belief was wrong, and every one was found by a human running a
runbook, not by the suite:

- Bitbucket threads by *immediate parent*, not by root (2026-07-25).
- GitLab's compare diff emits no `diff --git`, so it parsed to zero files and rewrote every
  `STILL_OPEN` to `UNCHANGED` on that provider alone (2026-07-26).
- GitLab's `newerThreadRef` compared opaque discussion ids as `BigInteger`, so the ADR-019
  reconciliation fix was inert on GitLab (2026-07-26).
- GitHub `resolveThread` reported `ALREADY_RESOLVED` when it matched nothing (2026-07-25).
- GitHub 403 is also a rate-limit signal, not only 429 (2026-07-21).

None of these were findable by a stub, because encoding them requires already knowing them. The goal
of this suite is to replace belief with observation for the one SCM where that is achievable
hermetically, and to turn Mode G from an operator's afternoon into a job.

## 2. Scope

**In:** GitLab, containerised (`gitlab/gitlab-ce`), the S1–S11 parity script from Mode G plus one
scenario Mode G's table implies but the first draft of this design dropped (§9, S9b), running against
the packaged stack in CI and locally.

**Out, deliberately:**

- **GitHub.** GitHub Enterprise Server is a licensed appliance VM, not a container. There is no
  self-hostable GitHub whose API our adapter targets. Stays on the manual runbook.
- **Bitbucket.** The self-hostable product is Bitbucket Data Center, whose REST API
  (`/rest/api/1.0/projects/{key}/repos/{slug}/pull-requests/{id}`) is a *different API family* from
  the Cloud API our adapter targets (`/2.0/repositories/{workspace}/{repo}/pullrequests/{id}`).
  Self-hosting Bitbucket would exercise an adapter we do not ship. Stays on the manual runbook.
- **Gitea / Forgejo as a GitHub stand-in.** GitHub-shaped, and divergent in precisely the places
  this project has been bitten — review threads, GraphQL resolve, multi-line anchors. A green run
  against Gitea would be confident and meaningless.
- **Review quality.** The suite asserts plumbing and protocol, never whether findings are good.
  See §6.

## 3. Constraints discovered in the code

### 3.1 Our own SSRF guard refuses a containerised GitLab

`PublicHttpsGuard.validate` requires `https` **and** an address that is not loopback, link-local,
RFC1918, CGNAT or IPv6 ULA. A Docker-network GitLab is `172.x`, so `isSiteLocalAddress()` rejects it.
A self-signed certificate does not help: the private-address check is the wall, not the scheme.

Verified: the guard fires at exactly three create/update sites (`ProviderResource.java:278`,
`LlmProviderResource.java:174`, `ContextProviderResource.java:418`) and nowhere on the review path.
The base setting is a literal `false` (`application.yml:186`) that `%prod` never overrides, so an
environment variable wins on config ordinal. The gateway and worker hold no copy and never
re-validate. The SCM clients' redirect guards skip same-host targets (`GitLabClient.java:151-159`),
so a Docker-network GitLab does not trip them either.

**Decision:** run the packaged stack with `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS=true`.

**Known deviation, recorded here because it must not be discovered later:** the suite therefore runs
a configuration no operator should run, and `PublicHttpsGuard`'s production behaviour is *not*
covered by this suite. It remains covered by `ProviderUrlValidationTest` in `testServices`.

**This section is correct as written, and a correction that was appended to it on 2026-08-30 was
itself wrong — both facts are kept, because the second is the more useful one.**

The wrong correction claimed that `spire-http`'s `PinnedJsonClient` applies a private-address check on
every request, and that this structurally blocks every context provider from reaching a
Docker-network GitLab. It does not. `isPrivateAddress` is reachable only from
`requireSafeRedirectTarget`, which runs only on a **3xx** and returns immediately for a same-host
target; its javadoc says plainly that dev/test legitimately run against WireMock on localhost. The
distinction it leaned on — that `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS` governs
`PublicHttpsGuard` (orchestrator, registration-time) and not the worker's transport — is true, and was
used to support a conclusion the code does not support.

What is actually observed is narrower and still unexplained: the `code` context provider runs,
extracts identifiers, and resolves none of them (`Context resolution for CODE: extracted=17
resolved=0`). The code-context probes (§9.2) are `@Disabled` on that basis, with the reproduction and
the first thing to check recorded in
`techdebt/global/3-3-code-context-resolves-nothing-in-the-e2e-stack.md`.

**The process lesson is worth more than the finding.** A plausible mechanism was reached for without
testing it, and it propagated into a techdebt entry, this spec, `CLAUDE.md`, a test's javadoc and a
pull request description before anyone read the guard. A four-lens review round did not uniformly
catch it either — the security lens read the claim and called it accurate; the QA lens read
`PinnedJsonClient` and found the guard unreachable on the direct path. Agents agreeing is not
evidence.

Rejected alternatives:

- *Seed the provider row directly into Postgres.* Keeps prod config intact, but the harness must
  reproduce Tink AAD-bound encryption by hand. It breaks silently whenever the encryption boundary
  moves, which is the worst failure mode available.
- *Run the dev stack instead.* `%dev` sets the flag natively and disables auth, so nothing is
  relaxed. Rejected because the packaged artifact — nginx single-origin, OIDC on every call, prod
  logging — is where the 2026-08-27 login defects lived, and this suite would be its only automated
  consumer beyond `deploy/e2e.sh`'s route checks.

### 3.2 GitLab's own outbound guard refuses us

GitLab blocks webhooks to local and private networks by default. Until
`allow_local_requests_from_web_hooks_and_services` is set, deliveries are refused at GitLab's end —
the exact symptom Mode G's troubleshooting section describes as "the bot went silent",
indistinguishable from a policy decline.

Setup must set it via `PUT /api/v4/application/settings`, and must **assert** it was set rather than
assume the call succeeded.

### 3.3 LLM output is unassertable

ADR-026 §9 measured the noise floor directly: rerunning an *identical* configuration produced five
differing findings. No assertion of the form "the review found N findings" can be stable. This is
what forces §6.

### 3.4 A `GenerateReview` on a follow-up commit makes TWO model calls

`ReviewWorker.reconcile` (`ReviewWorker.java:236-258`) issues a separate, claim-guarded call and
takes verdicts from `VerdictsParser.parse(...)`. Our code does not decide `RESOLVED`; the model does.
Our code only *downgrades* an untouched `STILL_OPEN` to `UNCHANGED` (`downgradeUntouched`).

This is the single most important fact in this document. The first draft did not know it, and every
error in §6 and §9 followed from that. Consequences are worked through in §6.

### 3.5 GitLab CE boot cost

Roughly 5 minutes and 4 GB, and that estimate is optimistic on a shared 4-vCPU runner unless the
image is trimmed (§11). `ubuntu-latest` is 4 vCPU / 16 GB, so it fits, but only as a nightly job. It
also decides the lifecycle model in §8.

## 4. Topology

`deploy/compose.e2e.yml`, layered over `deploy/compose.yml`. Nothing in the packaged stack's own
definition changes; the overlay adds services and publishes one extra port (§7.2).

| Service | Image | Role |
|---|---|---|
| `gitlab` | `gitlab/gitlab-ce`, pinned by tag + digest | A real GitLab. The independent oracle. |
| `llm-mock` | WireMock | Speaks the OpenAI-compatible wire. Returns fixtures. |

```
gitlab    --webhook-->  spire-ui:8080 (nginx)  -->  gateway  -->  orchestrator  -->  worker
worker    --review--->  llm-mock:8080/v1       -->  fixture responses
worker    --comments->  gitlab
harness   --assert--->  Postgres + REST API + GitLab API + llm-mock:8080/__admin/requests
```

**`llm-mock` is also the prompt observer.** WireMock keeps a journal of the requests it received, so
the harness can read the exact prompt text the worker sent the model. That is how the code-context
probes in §9.2 assert that a retrieved snippet reached the model — without enabling `PromptLog` and
without reading any internal state. It is the only observation point in the design that sees inside
the review, and it sees it the way the model does.

All on one Docker network, which is what removes the tunnel. Inbound reach to an ephemeral CI runner
is the single reason the GitHub and Bitbucket tiers cannot be automated the same way.

## 5. Setup phase

Runs once per stack. Ordered; each step asserts its own result.

**GitLab side:**

| Step | Mechanism |
|---|---|
| Wait for readiness | poll `http://gitlab/-/readiness` |
| Create `bot` and `human` users | `gitlab-rails runner` inside the container |
| Mint a PAT for **each** user with a known value | `gitlab-rails runner`, `token.set_token(...)` |
| Allow local webhook targets | `PUT /api/v4/application/settings` (§3.2) |
| Create project + starter files | `POST /api/v4/projects/:id/repository/commits` |

**Two users, and therefore two tokens.** The self-loop guard means the bot must not answer its own
comments; S3–S7 require posting notes *as* `human`. The first draft asserted the two-user
requirement and then minted only the bot's token, which would have left every conversation scenario
unrunnable.

**Commits go through the Commits API, not a `git` binary.** It is atomic, needs no working copy or
credential helper in the runner, and returns the commit sha the assertions need.

**Our side** — through nginx on `http://localhost:34700`, with a Keycloak operator token, reusing
`deploy/e2e.sh`'s `token()` helper. Note the prefixes differ per service (ADR-022): the orchestrator
answers under `/api`, the gateway under `/gw`.

1. **Register the GitLab provider** — `POST /api/providers`, `baseUrl: http://gitlab/api/v4`,
   token = `bot`'s PAT.
2. **Register the LLM provider** — `POST /api/llm-providers`, `baseUrl: http://llm-mock:8080/v1`.
   This call synchronously validates the key with `GET {baseUrl}/models`
   (`LlmKeyValidator.java:39`), so **`llm-mock` must stub `/v1/models` with a 200 before this
   runs** or setup dies here with a 400.
3. **Catalogue the model as `UNMETERED`.** ADR-023's pre-spend guard refuses an unpriceable model,
   so a review would be *refused* rather than run. `UNMETERED` states "this model is free" honestly
   instead of entering a fabricated price.
4. **Register the webhook** — `POST /gw/webhook-repos`. The response is `WebhookRepoSecret`: the
   view **plus the secret, returned exactly once** (`WebhookRepoRegistry.java:98`;
   `WebhookRepoView` carries only `hasSecret`). Capture both the routing key and the secret — GitLab
   needs the secret for its *Secret token* field.
5. **Create the project hook in GitLab** — url
   `http://spire-ui:8080/webhooks/gitlab/{key}`, token = the secret from step 4, with
   `merge_requests_events` **and** `note_events` enabled. Both, or the conversation scenarios
   receive nothing.
6. **Set review mode to `active`.**

Two steps the first draft planned are **not needed**, verified: the first LLM provider auto-defaults
(`LlmProviderRegistry.java:77`), and an empty author allowlist reviews everyone
(`IntegrationSaga.java:563-566`).

The setup path is itself a test: if provider or webhook registration regresses, nothing downstream
starts.

## 6. Steering the mock

### 6.1 Three call kinds, discriminated on the locked contract

There are three prompt kinds (`PromptKind`), and a follow-up commit exercises two of them in one
`GenerateReview` (§3.4). The mock must answer all three, in three different response *shapes*:

| Kind | Response shape | Used by |
|---|---|---|
| `REVIEW` | `{"summary": ..., "findings": [...]}` | S1, S8, and the second call of S9/S10 |
| `RECONCILE` | `{"verdicts":[{"id":n,"status":...,"note":...}]}` | first call of S9/S10 |
| `FOLLOWUP` | plain Markdown text, code in a ``` fence | S3–S7 |

**The discriminator is `PromptCatalog.lockedSystemSuffix`**, whose three values are textually
distinct (`PromptCatalog.java:21`, `:39`, `:42`). It is the right matcher precisely because it is
*locked*: per-repository prompt customization (E16) can rewrite a persona or body, so matching on
those would make the suite fail the day someone overrides a prompt — which is itself a supported
feature this suite should not be hostile to.

### 6.2 Markers match added lines only

The fixture repository carries marked defects:

```java
int x = 1 / 0;  // E2E-DEFECT-A
```

A test "fixes" a defect by deleting the marked line. Naive presence-matching inverts at exactly that
moment: the deleted line appears in the next incremental diff as a **removed** line, so
`prompt contains E2E-DEFECT-A` is *true* precisely when the defect is gone.

The rule is therefore `^\+.*E2E-DEFECT-A` — added lines only. The `RECONCILE` fixture is keyed the
same way and returns `resolved` for a marker no longer added, `still-open` for one still present.

### 6.3 Why the mock sits outside the process

`spire.llm.provider=stub` bypasses `TokenUsageMapper` and the `finishReason` → `outputCapped`
mapping, both inside `LangChain4jLlmProvider` (usage mapping at `:156`) — the layer the 2026-08-28
defects lived in. An out-of-process mock speaking the real wire keeps them under test for roughly
the same effort.

Correction to the first draft: the stub does **not** bypass `FindingsParser` — `ReviewWorker.java:200`
parses every `Completion`, the stub's canned JSON included. The conclusion stands; the supporting
claim was wrong and is not leaned on.

**What this suite therefore cannot prove:** that a real model can review anything. That stays a
human's job on the manual runbook. An unassertable property does not become assertable by being
placed in CI.

Rejected alternatives:

- *Real model, loop-only assertions.* S9/S10 verdicts become unassertable — and S9/S10 are where
  GitLab's worst shipped defect lived. The suite would miss the defect that most justifies it.
- *Record/replay of real responses.* Replay keys on prompt text, and this repository edits prompts
  often (per-repo prompts, the drift banner). Every prompt change would invalidate the corpus.

## 7. The async contract

### 7.1 Nothing is synchronous

Reviews complete through Kafka; GitLab delivers hooks through Sidekiq. Every assertion in §9 is a
race unless it waits. The contract, applied uniformly:

- **Presence assertions** poll the read model (or the GitLab API) to a target state with a per-step
  deadline, and fail with the step name and the last observed state.
- **Absence assertions** — S11's "no new `ReviewRequested`" — are vacuous when checked immediately.
  They must wait for the *positive* signal first (the PR badge flips `MERGED`), then hold a fixed
  quiet period, then assert the absence. An absence assertion with no anchoring positive signal is
  not an assertion.

Flake budget: this is a nightly job, so **one flaky step costs a day of signal**. Deadlines are
generous and uniform rather than tuned per step; a step that needs a special deadline is a signal
that something is wrong, not a tuning opportunity.

### 7.2 Reading Postgres

`deploy/compose.yml` publishes only `spire-ui` (34700) and Keycloak (34767); Postgres has no host
port. Two options, and the design picks the second:

- Publish 5432 from the overlay. Simple, but it weakens "the overlay changes nothing" and exposes a
  database port on a developer's machine.
- **Shell in, as `deploy/e2e.sh:135-139` already does** — `docker compose exec postgres psql`. No
  new exposure, one established idiom, and the superuser from `deploy/.env` can read both the
  `orchestrator` and `worker` schemas, which per-service roles cannot.

Cost, stated: assertions run through a subprocess rather than JDBC, so results are parsed text. Each
query gets a small typed reader rather than string matching at the call site.

## 8. Lifecycle

The harness starts nothing. A compose command brings the stack up; `testE2e` asserts health and
fails fast with a clear message if it is not up.

```bash
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env up -d --build
./gradlew testE2e
```

Rejected: Testcontainers `ComposeContainer` owning the lifecycle. It is more hermetic, and it makes
every local iteration pay §3.5's five minutes. Every defect in this project's history was found by a
human iterating against a live stack, so the iteration loop is the one to optimise. CI pays the cold
start exactly once either way.

Each run creates a **fresh GitLab project**, so no state carries between runs, and a run deletes
projects older than its own on entry — otherwise a long-lived local stack accumulates them
indefinitely. Mode G requires fresh PRs for the same reason: S5's turn counter and S9/S10's
reconciliation mean nothing from a resumed review.

## 9. The scenarios

Three merge requests, in this order: the main chain, then two context probes, then the rename. Only
the first is a long ordered chain; the other three are short and independent, which is deliberate —
each isolates a failure the chain would otherwise absorb.

### 9.1 The main chain (MR 1)

Mode G's order. Conversation scenarios precede the fix commits, because the fixes change the code
and resolve the threads.

MR 1 is **mixed-language**: it touches Java and TypeScript files in one diff. Four marked defects —
A and B in the Java file, C in the TypeScript file, D introduced later by S10. A mixed diff is
realistic, and it is the shape in which the two independently-maintained extension maps
(`Languages.BY_EXTENSION` and `CodeContextProvider.LANGUAGE_BY_EXTENSION`, see
`techdebt/global/3-2-code-extension-map-duplicated-with-no-drift-guard.md`) would show disagreement.

| # | Action | Assertion |
|---|---|---|
| S1 | Open MR | one inline comment per finding + exactly one summary, present on GitLab |
| S3 | `human` replies under a finding | bot answers in that thread; code arrives in a fenced block |
| S2 | Fetch the thread | full text returned, not the ≤160-char preview |
| S4 | Reply to the bot's answer, twice | one conversation, not split; turns accumulate on the root |
| S5 | Reply to the cap, then once more | notice posted exactly once; @-mention still answered |
| S6 | New thread on an unflagged line, @-mention | bot answers; no finding created |
| S7 | Plain MR comment | answered in the summary thread |
| S8 | Post `/review` | summary comment updated in place, never duplicated |
| S9a | Delete defect A, push | `RESOLVED`; thread resolved **on GitLab**, not just in our read model |
| **S9b** | **Partially fix defect B in the same push** | **verdict stays `STILL_OPEN`, with a note naming what remains** |
| S10 | Fix the rest over commits; introduce defect D | D appears as a new finding with its own thread; ends `openFindings: 0` |
| S11 | Merge the MR | badge flips `MERGED`; then, after a quiet period, no new `ReviewRequested` |

**S9b is the load-bearing assertion and the first draft did not have it.** The draft asserted that
untouched findings stay `UNCHANGED` — which is correct with or without the GitLab compare-diff
regression, because `downgradeUntouched` only rewrites findings whose hunks the diff says were
touched. When the compare diff parses to zero files, *every* file reads as untouched, so untouched
findings still read `UNCHANGED` and the assertion passes. Only a **touched-but-unfixed** finding
surviving as `STILL_OPEN` can fail under that regression. Mode G's own S9 row says so; the draft
dropped that half.

S5 pins the turn cap low via the conversation settings rather than reading the configured value, so
the scenario does not depend on an operator-tunable number.

**Every assertion is dual-sourced.** Our read model (`review_thread`, `review_event`) says what we
believe happened; the GitLab API says what happened. A resolve that degraded to reply-only writes
`ThreadReplied` rather than `ThreadResolved` on our side — but only GitLab can confirm the thread is
actually resolved. Asserting one without the other is how the `resolveThread` `ALREADY_RESOLVED`
defect survived.

**MR 1 is one ordered chain, not twelve independent tests.** S5 needs S4's turns; S9 needs S1's
findings. A break in S1 reddens everything after it. Accepted, with three mitigations: every failure
message leads with the step name, §11's diagnostics run on failure so the report names the first
broken step *and* carries the logs to diagnose it, and the two riskiest concerns — per-language code
context, and the rename — are pulled out into their own MRs below rather than left inside the chain.

### 9.2 Code-context probes (MRs 2 and 3), one per language

The review loop does not branch on language, so running the whole chain twice would test the same
code twice. What genuinely differs per language is the code-context path: import parsing, the symbol
index, caller lookup, and the two extension maps. Each probe is therefore one short MR that asserts
only that path.

Each probe repository holds three files in its language: a **definition** file, a **changed** file
that imports it, and a **caller** file that references the changed file's symbol. Open the MR, wait
for the review, then read `llm-mock`'s request journal (§4) and assert the prompt the worker sent
contains:

- the definition's body — rung 1, the import resolved and the snippet was fetched;
- the caller's path — rung 2, the symbol index named a real caller.

Asserting on the prompt rather than on the findings is the point: a finding is the model's opinion
and unassertable (§3.3), while the prompt is a fact about our code. This also discriminates, which a
presence-only check would not — a probe that passes with `contextRef` nulled is measuring nothing,
and the implementation must verify it fails in that case, as
`ReviewWorkerTest.aCodeSnippetReachesThePromptSentToTheModel` already does at the unit level.

Rung 2 needs the index populated, and the index only knows files reviews have read. The probe
therefore reviews once to populate, then pushes a second commit and asserts the caller citation on
the *second* review. A first-review assertion would fail for a correct implementation.

### 9.3 The rename (MR 4)

Mode G defers renames to its final round, and the repository does not agree with itself about why:
`CLAUDE.md` records a 2026-07-26 pass where a 100%-similarity rename did **not** churn finding
identity, while `SMOKE-TEST.md` calls the churn a known limitation and cites a `techdebt/` entry that
does not exist. Nobody currently knows which is true, which is the strongest possible argument for a
test.

MR 4 is its own MR for that reason: open, review, rename the file, push, then assert **the correct
behaviour** — findings follow the file to its new path, resolve there, never return as new, and never
report `SUPERSEDED` (the code moved; it did not disappear).

**This assertion may fail on first implementation, and that failure is the deliverable.** It is a
reproduction of a defect nobody has pinned down, not a broken test. Two consequences: it lives in its
own MR so a red rename cannot mask the chain, and it must not be marked skipped or expected-to-fail —
a suppressed assertion would restore exactly the state of not knowing that made it worth writing. If
it goes red, the fix follows as separate work.

## 10. Module and CI

New Gradle module `spire-e2e`, new task `testE2e`. Excluded from `testFast` and `testServices`, both
of which stay fast enough for the pre-commit loop.

- **`TestTierCoverageTest` must be amended first, not merely appended to.**
  `spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java:49-74` requires every module
  with tests to be in **exactly one of two** lists. There is no third tier. The first draft claimed
  `spire-e2e` is excluded from both *and* added to that test, which cannot both hold. Adding an e2e
  tier to that guard and to the root build is a prerequisite task, not a footnote.
- `spire-arch`'s provider-neutrality scan covers core modules only. `spire-e2e` is not core, so
  naming GitLab in it is correct and needs no allowlist entry.
- `LICENSING.md` requires every module to carry a licence. `spire-e2e` needs one assigned. It
  depends on no service module: it drives the stack over HTTP and a `psql` subprocess only. That is
  also what keeps it honest — it cannot reach into our code, so it can only assert what a real
  operator could observe.

CI: a second job in `.github/workflows/e2e.yml`, already nightly cron + `workflow_dispatch`
(`e2e.yml:11-14`). Never on the PR path.

## 11. Operational requirements

These are not polish. Each one is the difference between a failing nightly job that can be diagnosed
and one that cannot.

- **Diagnostics on failure.** Capture `docker logs` for gateway, orchestrator and worker, and the
  GitLab project's webhook-delivery list. Mode G's troubleshooting greps are the specification for
  what to capture. Without this, a nightly failure is a red square.
- **Pin `gitlab/gitlab-ce` by tag and digest.** Detecting GitLab's own drift is a goal, but an
  unpinned nightly failure is unreproducible. Pin, and bump deliberately — a bump that breaks the
  suite is exactly the signal worth having.
- **Trim the GitLab image via `GITLAB_OMNIBUS_CONFIG`** — registry, monitoring and Mattermost off.
  §3.5's five minutes is optimistic otherwise.
- **Purge old projects** on run entry (§8).

## 12. Still open

1. **JavaScript is a supported language with no probe.** `spire-context-code` ships
   `LanguageSupport` for `java`, `typescript` and `javascript`; §9.2 covers the first two. A third
   probe is nearly free once the shape exists, but `.js`/`.jsx` resolution differs enough from
   TypeScript's (no `.tsx`, different index resolution) that it is a separate fixture, not a file
   extension change. Decide before implementation whether to ship two probes or three.
2. **Runtime budget.** Four MRs — one long chain, two probes, one rename — each waiting on real
   webhook delivery and two model calls per round. The chain was estimated at ~3 minutes when it was
   the only MR. The total needs measuring against the nightly job's practical ceiling before the
   scenario set grows further.

Resolved since the first draft, recorded so they are not reopened: fixture language (answer: mixed
in MR 1, plus per-language probes, §9.1–9.2), the turn cap (answer: pin it low, §9.1),
`llm_charge` under `UNMETERED` (answer: assert rows exist with zero cost), and the rename (answer:
test it, in its own MR, asserting correct behaviour, §9.3). The genuinely load-bearing unknown the
first draft failed to name was §6.1 — how the mock discriminates three call kinds — which the second
revision resolved.

## 13. Review history

Reviewed adversarially on 2026-08-29 against the codebase. Twelve findings; one blocker, five major.
What changed:

- §3.4, §6.1 — the two-call reconcile path, previously unknown to the design. This invalidated the
  original steering mechanism entirely.
- §6.2 — marker matching inverted on the reconcile path; now added-lines-only.
- §9 S9b — the original S9 assertion passed vacuously under the exact regression it targeted.
- §7 — the async contract, previously absent; every assertion was a race.
- §5 — `human`'s PAT, the `/v1/models` stub, the webhook secret, the `/gw` prefix, the `:8080` port
  and the two hook event types; two planned steps removed as unnecessary.
- §7.2 — Postgres is not reachable over JDBC from the host.
- §10 — `TestTierCoverageTest` has two tiers and must be amended.
- §6.3 — the stub does not bypass `FindingsParser`; claim corrected.
- §11 — operational requirements, previously absent.

Verified correct and unchanged: the SSRF analysis and the env-var override (§3.1), the endpoint
inventory (§5), the scope exclusions (§2), and the CI placement (§10).

### Revision 2 — 2026-08-30

Settled the two questions the review round left open, and one finding that fell out of settling them:

- §4 — `llm-mock`'s WireMock request journal is the prompt observation point. This was not in either
  earlier draft, and it removes the need for `PromptLog` (opt-in, off by default) to be enabled just
  so a test can see what the model was sent.
- §9.1–9.2 — one mixed-language chain plus two per-language code-context probes, rather than running
  the whole chain per language. The loop does not branch on language; the context path does.
- §9.3 — the rename gets its own MR and asserts correct behaviour, on the operator's decision that a
  failure here is a wanted reproduction rather than a reason not to test. Recorded because the
  repository currently contradicts itself about whether the defect exists at all.
- §12 — JavaScript, the third supported language, has no probe. Surfaced by scoping the other two.
