# Factory M3.5 — one ticket to a build

**Status:** design, 2026-09-16, revised the same day after the first review (§10 lists what changed).
**Parent:** the [operator experience specification](2026-09-15-factory-operator-experience-design.md),
§2.4 and §6(b), with the operator's decisions 1B, 2A, 3A and 4B (§7 there). Part A and part E of that
slice shipped with M3. **New here:** part F, Codex subscription sign-in. The operator asked for it on
2026-09-16: *"It should be available as an option but I would prefer to test both scenarios: with API
tokens and with a subscription."*

## 1. Goal and exit

The operator writes one ticket, applies one label, approves one plan, and gets one build. Nothing else is
typed.

**Exit criterion.** On `spire-test`, in a repository with build defaults, a ticket that carries its own
acceptance criteria and the `spire:assisted` label produces an open plan gate. Its panel shows the
composed step, the base commit, the harness, the model, how the run is paid, and the caps. Approving it
produces one held build. This is measured twice:

| Run | Paid with | Must show |
|---|---|---|
| 1 | API key | a known cost in millicents; no `run_usage_unknown` stop |
| 2 | Codex subscription | real token counts, cost recorded as an asserted zero (`UNMETERED`), no per-token spend |

`suggest` stops with the composed plan visible. `autonomous` builds without the gate.

**Not in M3.5.** Verify, the pull request and land are M4 (decision 5A: verify is M4's first slice).
After M3.5 the item still stops at `verify / capability_unavailable`, and that stop is expected. Generated
specifications and plans are M4 (FR-F18/F19); they will write into the slot part C creates.

## 2. Order

| # | Part | What it removes for the operator | Size | What the operator supplies |
|---|---|---|---|---|
| 1 | B — build defaults | typing base, harness and model per item | M | the defaults, once per repository |
| 2 | D — pricing completeness | the `run_usage_unknown` stop on an API key | M | the vendor's rate, or "not billed", per token type |
| 3 | F — Codex subscription | per-token spend for Codex | L | one dedicated Codex sign-in |
| 4 | C — system-composed preparation | the spec ticket, the plan ticket, the JSON, the form | M/L | nothing |
| 5 | P — live proof | — | S | two labels, two approvals |

B comes first because C and F read it. D comes before F because F's pool selection reuses D's priced
check to decide what an API-key run may start. C comes last: it prepares items without a person, so the
dispatch checks it relies on (D, F) must already tell the truth.

## 3. Part B — repository build defaults

**What the operator sees.** The repository Factory tab gains step 5, "How it builds":

- **Base branch** — text, with "Check" that reads the branch's head.
- **Harness** — a select of the configured agent images (`FactoryConfig.agentImage` keys).
- **Model** — a select of enabled models. After D, a model whose pricing is incomplete for the chosen
  harness is disabled and names the missing token types.
- **Pay with** — API key or Codex subscription. After F, the subscription choice is offered only for a
  harness that supports it and only when a sign-in exists.

**A repository-scoped head read.** The M3 endpoint (`WorkPreparationResource.head`) needs an existing
item and passes its evidence check first, so it cannot serve a repository being set up before any ticket
exists. B adds `GET /api/repositories/{id}/factory/branch-head?branch=…` with the same answers
(`branch_head_unsupported`, `branch_head_unconfirmed`, `repository_account_missing`). It resolves the
FACTORY account and falls back to REVIEWER for this read only, exactly as the item endpoint does
(`WorkPreparationResource.java:66-68`), because a read of a branch head needs no push right; the
fallback is stated on screen.

**Storage.** A new table `repository_build_defaults` (repository id as key, base branch, harness, model,
revision, updated by, updated at). It is configuration, not history. A preparation pins copies of these
values, and the gate binds the copies (ADR-045), so a later change never alters an open gate. The
revision takes part in the authority check of part C (§6). The billing column arrives with part F: a
column whose only accepted value is `API_KEY` would offer a choice the deployment cannot honour.

**API.** `GET` and `PUT /api/repositories/{id}/factory/build`, `spire-admin`, with an expected revision.
Refusals name the rule: `base_branch_blank`, `harness_unconfigured`, `model_unknown`,
`billing_unsupported`.

**Readiness.** Admission does not need these coordinates, so part B does not make a repository "not ready"
without them: the outcome line adds one sentence saying each ticket still has its build coordinates typed
by hand. Part C promotes them to a requirement, because that is when their absence stops the automatic
path.

**Manual form.** The prepared-task form fills its fields from the defaults.

## 4. Part D — pricing completeness

This follows the parent's §5, with decision 4B.

- **What a harness can report.** The codex adapter maps **five** buckets: `INPUT`, `CACHED_INPUT`,
  `CACHE_WRITE`, `OUTPUT`, `REASONING` (`CodexAdapter.java:275-279`). Every observed run so far reported
  zero cache writes, and the adapter's own comment says that alternative is not ruled out
  (`CodexAdapter.java:248-252`), so completeness covers what the harness **can** report, not what one run
  happened to report. Declaring four would let the first non-zero cache write become an unknown line
  (`LlmModelPricer.java:91`) and stop the item after it had spent. The orchestrator does not depend on
  `spire-harness` today (`spire-orchestrator/build.gradle.kts`), so the declaration lives where the
  orchestrator can read it; a test pins it against the adapter's own mapping, with a non-zero cache write
  in the fixture.
- **The "not billed" assertion, carried end to end.** A rate becomes a typed value: `RATED` with a rate
  above zero, or `NOT_BILLED` with no rate. That type must survive every layer, because the existing ones
  all assume a positive number: the column is `NOT NULL CHECK (> 0)`
  (`V30__llm_charge_ledger.sql:46-53`), the validator rejects null and zero
  (`LlmModelPricingValidator.java`), the API exposes maps of numbers (`LlmModelInput`, `LlmModelView`),
  the UI validates `> 0` (`SettingsLlmModelRateFields.tsx`), and — the trap — the repository reads
  `rs.getLong(…)` with no `wasNull` check (`LlmModelRateRepository.java:34`), so a nullable column alone
  would turn a `NOT_BILLED` assertion into a silent zero rate. Existing rows backfill as `RATED`. A type
  that is missing stays unknown, never zero.
- **The ledger line.** A `NOT_BILLED` bucket charges an `UNMETERED` line: rate 0 and cost 0, which is what
  the ledger's own check requires (`V30__llm_charge_ledger.sql:94-95`). Copying a null rate into the line
  would violate it.
- **Which types may be asserted: any of them.** The first draft allowed only the optional three and
  pointed a vendor that bills nothing for `INPUT` at `UNMETERED` — which asserts zero for the WHOLE
  model and would erase the `OUTPUT` charge that vendor does make. What the rule enforces instead is
  that `INPUT` and `OUTPUT` are SAID, one way or the other: a rate, or the mark. Silence on either one
  is refused, because a call reporting an unsaid type cannot be priced.
- **A model switched off cannot start a run.** Dispatch priced a model by name and never read `enabled`,
  so a model an operator had switched off kept running while the setup screen refused to save it. The
  three dispatch sites now refuse it (`model_disabled`). A model the catalogue never had is left to the
  pricing refusal, which names what it cannot price rather than calling a typo "switched off".
- **Rolling back.** V74 is forward-only once an assertion is saved: the previous reader turns a
  `NOT_BILLED` row into a zero rate. The migration says so at the top, and an older dashboard that omits
  `notBilled` on a model save clears the assertions, because the payload is a full replacement.
- **One predicate.** `isPriceable(model, harness)`: every bucket the harness can report has a rate or a
  not-billed assertion. The three dispatch sites (`WorkRunAssembly.java:46`, `FixRunDispatcher.java:288`,
  `RunResource.java:225`) refuse with `model_pricing_incomplete` and the list of missing types. The review
  predicate `isPriceable(model)` stays for the reviewer, which is not a harness. The refusal carries the
  types with it (`model_pricing_incomplete:CACHED_INPUT,REASONING`), and the work item keeps that reason
  instead of the one word every cause used to collapse into.
- **Where the operator sees it.** Settings → LLM: each token type takes a rate or "The vendor does not
  bill this", and a model lists its gaps. Factory step 5 and the preparation select show the gap. The
  attention panel raises one row per repository whose build defaults cannot be priced; it keeps
  delegating to the pricer (`AttentionQueries.java:133`) rather than testing rates itself, and it stays
  silent for a repository that pays by subscription — for those it reports a missing sign-in instead.
- **The system never types a price.** The operator enters the vendor's published rate.

**Acceptance** is the parent's §5 check, plus a round trip of mixed `RATED` and `NOT_BILLED` types and one
model with a type left unset.

## 5. Part F — Codex subscription sign-in

### 5.1 Why it does not exist today

ADR-031 carries subscription auth as an operator-owned mode for Codex. The operator's answer from OpenAI
support (recorded 2026-09-01) settles the terms question for this deployment. RUN-TOPOLOGY §1.1 measured a
containerised Codex answering on a subscription, and §10 assumed Codex runs on one.

The build shipped only the API-key path:

- `harness_credential.api_key` holds one key (`V52__harness_credential.sql`).
- `CodexAdapter` pipes it into `codex login --with-api-key` (`CodexAdapter.java:108-131`).
- Charging selects a price by **model** (`RunCharges.java:102`), not by the credential that paid, so a
  subscription run would be priced per token like any other. (`RunCharges.record` does skip starts and
  proven pre-agent failures, `RunCharges.java:88`, and the catalog already supports an `UNMETERED`
  model, `LlmModelPricer.java:65` — but that is a property of the model, which both modes share.)

No storage kind, injection, charging rule or screen exists for a sign-in file.

### 5.2 What the operator does

1. **Once, on their own machine:** sign in to Codex into a folder used only by the factory, for example
   `CODEX_HOME=<folder> codex login`. Do not use the everyday `~/.codex` folder. Two programs that share
   one sign-in may lock each other out when one of them refreshes the token (**not verified**; F0
   measures it).
2. **Settings → Harness credentials → Add Codex subscription:** a label and the contents of `auth.json`.
   The screen never shows the contents again. The same screen lists and manages API keys, which today
   have no screen (`techdebt/spire-ui/4-3-three-factory-surfaces-still-have-no-screen.md`).
3. **Factory tab step 5:** Pay with → Codex subscription.

The screen recommends a ChatGPT seat used only by the factory (see 5.9).

### 5.3 F0 — measure before building

Use the pinned `spire-agent-codex` image (`@openai/codex@0.146.0`), the operator's dedicated sign-in and
one TEST prompt. Measure:

1. A container with only a copied `auth.json`, and no login step, runs `codex exec --json` to completion.
2. Whether a run rewrites `auth.json`, and when (`last_refresh`).
3. After a refresh, whether the previous refresh token still works.
4. Whether any cheap CLI command renews the sign-in **without** spending model quota.
5. What a usage-limit refusal looks like on the NDJSON stream and in the exit code, so that the pool can
   tell `rate_limited` from `rejected`.
6. Which usage buckets a subscription run reports.

Answers 2, 3 and 4 choose the refresh path in 5.6. The measurements go into EXECUTION-LAYER §3.3 with
their date and the CLI version.

### 5.4 Storage, identity and selection

- **Migration.** `harness_credential` gains `auth_mode` (`API_KEY` or `SUBSCRIPTION`; existing rows become
  `API_KEY`). `api_key` is renamed `secret`. `base_url` may be empty for a subscription. `account_ref`
  holds the ChatGPT account id claim, never an e-mail address, and is **unique among subscription
  members**, so the same sign-in cannot be uploaded twice under two labels and then leased twice.
- **Upload check.** The file must be a JSON object with its tokens present and a readable account id. It
  is stored Tink-encrypted with the row id as AAD, as keys are today. A file that carries an API key
  shape is refused rather than stored as a sign-in.
- **Selection.** `select(harness, billing)` picks only members of the requested mode, and a `SUBSCRIPTION`
  member serves only a harness that declares subscription support. Least-recently-exhausted order does
  not change. **Every existing caller keeps API-key selection** — `FixRunDispatcher.java:174`,
  `RunResource.java:377` and `WorkRunAssembly.java:48` all call one untyped pool today, so adding
  subscription members must not change what `/fix` and standalone runs select.

### 5.5 The lease is on agent activity, not on the item

A sign-in serves one agent at a time. The lease must therefore start when the agent container starts and
end when that container is gone — not when the item finishes:

- A **successful held build returns `RunWorkReady`** and no terminal publication result
  (`RunLauncher.java:226-232`), and M3.5 then stops at unavailable verification. A lease released only on
  a terminal result would stay held for days.
- The **orphan watchdog stops and preserves a held unit without a terminal result**
  (`OrphanWatchdog.java:205-212`), and it can report a failure *before* the unit is stopped
  (`OrphanWatchdog.java:245-252`), so "released when the watchdog reports" can overlap a stopped-but-alive
  agent with the next run.

So the lease is **fenced and bounded**: `leased_by_run`, `lease_version`, `leased_until`. It is released
when the worker confirms the agent container is gone, at the same point that decides the run's outcome,
and otherwise it expires. A release carrying an old `lease_version` is ignored. The publisher resume path
takes no sign-in and never replays the agent (`WorkRunWorker.java:91`). Cases the tests must cover:
duplicate command delivery, worker death before release, a failed stop, a late release from an old
lease, dispatch failure before the container exists, and two uploads of the same sign-in.

### 5.6 Injection and refresh — the agent never hands a credential back

- The worker passes the credential kind beside `HarnessInvocation.CREDENTIAL`. `CodexAdapter` writes the
  file to `$HOME/.codex/auth.json` with mode 0600 through a pipe, never through argv. Then it starts
  `codex exec` without a login step.
- **The container's copy is one-way.** The agent has a full shell (`CodexAdapter.java:105,127`) and runs
  ticket text, so anything it writes is attacker-controlled. Accepting a rewritten `auth.json` back — even
  with a matching account claim and a newer timestamp — lets a prompt-injected agent store a broken
  refresh token and a future timestamp, which locks every later run out of that sign-in and blocks
  honest updates. The design therefore **discards whatever the container writes**.
- **Renewal, path (a): a trusted refresh unit.** If F0 answer 4 finds a CLI command that renews the
  sign-in without spending quota, renewal runs in its own unit: the same image, no repository, no
  workspace mount, no prompt, no ticket text — nothing untrusted enters it. Its output returns over the
  encrypted credential channel and replaces the stored sign-in under a **server-owned version**, never an
  agent-supplied timestamp.
- **Renewal, path (b): none.** If no such command exists, the stored sign-in is used until the vendor
  refuses it. The member then becomes `rejected` with "Sign in again", an attention row appears, and the
  operator uploads a fresh file. F0 answer 2 gives the expected interval.
- **Secret handling either way.** The sign-in never enters argv, transcripts, run results, the outbox or
  a dead-letter record. `RunFailures` scrubs one neutral credential string today
  (`RunFailures.java:116`); for a sign-in it must scrub each token inside the file, because an echoed
  access or refresh token is not the whole string. The API-key path stays exactly as it is.

### 5.7 Money, and what the caps still do

- The **run** records which mode paid it. Charging reads that, not the model
  (`RunCharges.java:102` prices by model today), so one model can serve an API-key run and a subscription
  run in the same deployment — which the live proof needs. Marking the shared model `UNMETERED` instead
  would zero the API-key half of the proof.
- A subscription run's lines are `UNMETERED`: rate 0, cost 0, with the real token counts. Downstream
  readers already treat that as a known zero rather than unpriced (`ChargeLine.java:43`,
  `FactoryRunProjection.java:265`, `RunSpendReader.java:15`, `RunSpend.java:12`,
  `WorkItemRunBridge.java:108`).
- **Missing usage is still UNKNOWN** (`LlmModelPricer.java:44`). A subscription does not make an
  unreported run free; it makes a reported run cost zero.
- **The caps still act.** `WorkProgress.within` checks cumulative money, runs, calls and wall clock on
  every phase (`WorkProgress.java:38`), and re-admission preserves progress (`WorkItemEvent.java:57`). A
  subscription run adds zero money, so it cannot trip the money cap — but an item that already spent up
  to its cap stays stopped even if its next build would be subscription-funded. The deployment spend gate
  is still consulted (`WorkRunAssembly.java:47`). This is the intended policy, and it is stated on screen
  rather than quietly changed.
- Run detail says "Billed to: Codex subscription `<label>` · no per-token price".

### 5.8 Quota feedback needs a path, not just an observation

`RunCredentialFeedback` reacts only to `CREDENTIAL_REJECTED` (`RunCredentialFeedback.java:55-64`), and the
harness path collapses provider failures into `MODEL_UNAVAILABLE`. Measuring the NDJSON shape in F0
changes nothing by itself. F names the whole path: the adapter's classification, the harness-to-contract
translation, a retry-time field on the refusal, durable handling of that result, attribution to the leased
credential **version**, and the matching change to the arch guard
`spire-arch/src/test/java/dev/codespire/arch/CredentialRefusalHasNoProducerTest.java`.

### 5.9 Risks, stated plainly

- **The sign-in file is a person's account credential,** placed in a container that runs ticket text. A
  prompt-injected agent can read it, as it can read the API key today. What that token can reach beyond
  Codex is **not verified**. A seat used only by the factory limits the damage.
- **Terms.** The position rests on the 2026-09-01 support answer (ADR-031). Re-read the published terms
  before part P and record the date.
- **Revocation.** OpenAI can switch the mode off server-side. Every arm keeps working on an API key
  (ADR-031), so the operator changes a credential, not the product.

## 6. Part C — system-composed preparation

Decision 1B: the system stores the specification and the plan. Decision 2A: the base commit is pinned at
preparation.

### 6.1 What it does

- **Trigger.** An item sits at `spec / awaiting_input / specification_required`, its repository has build
  defaults, and its source can fetch the ticket. A scheduled sweep prepares it. The manual form stays as
  an override.
- **Specification.** The ticket's title and body at that moment, stored encrypted as an immutable row.
- **Plan.** `{"schemaVersion":1,"specificationSha256":…,"steps":[{"id":"step-1","instruction":"Implement
  the specification in full, and change nothing it does not ask for."}]}` — the schema the manual path
  already validates, so `WorkArtifacts.observe` keeps one rule set. The instruction does not repeat the
  specification: the dispatch prompt already carries both texts, so repeating it would pay twice.
- **Base.** The head of the default base branch, read through the FACTORY account
  (`DiffSource.fetchBranchHead`). Pinning the observed head is the decision; nothing claims to hold a
  lock on the remote branch.
- **Registered by** `system:build-defaults@<revision>`, so the item's history says which saved setup the
  coordinates came from.
- **A readable copy on the ticket.** One tracker `COMMENT` effect through `WorkSourceEffects`, when the
  source has that capability, naming the specification digest, the one step, the base, the harness, the
  model, and that editing the ticket does not change what was prepared. Without `COMMENT`, the dashboard
  only. It is queued as an effect: a comment that fails must never undo a registration that succeeded.
- **The sweep registers through {@code WorkItemTransitions.prepare}**, the manual form's own entry
  point, so it inherits that path's authority protocol whole rather than restating it (§6.4).

### 6.2 A stored artifact has its own immutable identity

Looking a stored artifact up by item, generation and kind does not survive the system's own rules:
re-admission carries a preparation into the next generation (`WorkItemEvent.java:57`), and re-preparation
can happen inside one generation (`WorkItemTransitions.java:340-352`). A row replaced in place would
destroy the bytes an earlier gate or a held run still binds.

So: `work_item_artifact(id UUID primary key, work_item_id, kind, ciphertext, sha256, created_at)` —
**insert only**. A new preparation writes new rows; old rows stay for the gates and proofs that bind them.
`WorkPreparation.Artifact` gains `origin` (`TRACKER` when absent, `STORED`) and a stored id. **Built:**
`location` stays present for BOTH origins — a composed artifact keeps the ticket it was composed from,
so a decision can still be traced to the ticket a person wrote, and no screen has to handle a null
location. Reads are authorised against the owning item.
`WorkArtifacts.observe` reads a `STORED` artifact from that table and checks its digest exactly as it
checks a ticket body today. The UI dereferences `artifact.location.link` unconditionally today
(`DecisionEvidence.tsx:47`, `WorkItemSteps.tsx:57`) and must render a stored artifact as text with its
digest instead. The manual path — `WorkPreparationResource.Input`, `WorkItemPreparation.tsx`,
`workPreparationApi.ts` — carries the new fields explicitly.

### 6.3 The binding is versioned, so old approvals keep their hash

`WorkGate.artifactOf` stores `preparation.binding()` in the gate (`WorkGate.java:18-22`), answering a gate
compares the stored artifact against the **recomputed** binding (`WorkItemTransitions.java:243`), and a
build proof compares its persisted `preparationBinding` the same way (`WorkItemTransitions.java:190`,
`WorkDelivery.java:200`). Adding a field to `binding()` therefore supersedes every open gate and
invalidates every held run, silently.

`WorkPreparation` gains `bindingVersion`. Version 1 is exactly today's algorithm over today's fields, and
a stored preparation without the field decodes as version 1. Version 2 adds the artifact origin and stored
id and the billing mode, and only preparations registered after this slice use it. Fixtures: an old
preparation JSON with an open gate, and one with a held run, both still matching after the upgrade.

### 6.4 The sweep uses the registration authority protocol

An expected item revision alone does not detect a policy, source, account or defaults change whose new
observation has not reached the item yet. The manual path already does more: `current(c, observed)` locks
and compares the source version and policy revision (`WorkItemTransitions.java:378-382`), then locks the
item and checks revision, status, attempts and gate (`WorkItemTransitions.java:343-352`).

The sweep uses that same protocol, in that same lock order, plus the build-defaults revision, and it
re-checks its own trigger predicate under the item lock. The stored artifact rows, the preparation
decision, the gate change and the comment intent commit in one transaction, so a lost race cannot leave a
winning event pointing at losing bytes. Separate tests cover a racing manual registration, a policy or
defaults change, takeover, suspension and re-admission — each one alone, so no second refusal masks the
one under test.

### 6.5 Preparation health is separate from the workflow reason, and retries are bounded

If a failed attempt wrote its reason into the item, the sweep's own trigger would stop matching and the
item would never be retried — and reconciliation does not repair it, because identical policy, authority
and issue return the previous item unchanged (`WorkItemLifecycle.java:14`), and "the repository gained
defaults" is not one of those observations.

So preparation health is its own record per item and generation: last attempt, refusal reason, attempt
count. The list and the detail page show the reason. The sweep revisits an item whose workflow state is
still `awaiting_input / specification_required` and whose last preparation refusal is retryable, with
bounded backoff, and it retries immediately after the operator repairs defaults, pricing or a sign-in. It
never revisits an item that is manually prepared, active, suspended, retired, or in a newer generation.

Refusal reasons, each with its sentence on screen: `ticket_body_empty`, `ticket_body_too_large` (today's
bound, 48×1024 characters), `build_defaults_missing`, `branch_head_unconfirmed`,
`model_pricing_incomplete`, `subscription_unavailable`, `artifacts_unavailable`.

### 6.6 A refusal at dispatch must still say what it is

An approved plan that stops at dispatch with no explanation breaks the one-ticket promise.
`WorkRunDispatcher` catches an assembly refusal and persists `build_configuration_unavailable`
(`WorkRunDispatcher.java:77`), discarding the reason. C adds a shared, non-consuming preflight used by
the defaults screen, the preparation screen and the sweep; dispatch keeps its authoritative rechecks, and
it persists the structured reason and detail it received. Retry after transient pool exhaustion must not
create a second paid attempt.

### 6.7 A ticket edited after preparation

The stored form stays what the gate binds. The detail page says "The ticket changed after it was
prepared" and offers "Prepare again", which supersedes an open gate and writes new artifact rows.

## 7. Part P — live proof on `spire-test`

1. Write two TEST tickets with acceptance criteria. Build defaults: harness `codex`, a priced model.
2. **Run 1:** Pay with → API key. Apply `spire:assisted`. Check that the plan gate shows the composed
   step. Approve. Check for one held build with a known cost.
3. **Run 2:** Pay with → Codex subscription, same model. Same steps. Check for one held build with real
   token counts and an `UNMETERED` cost of zero.
4. Count the human steps: write, label, approve. There must be no others.
5. Record the item, gate and run ids, the spend and the token counts in `docs/HISTORY.md` and an M3.5
   acceptance record. Update `docs/UNVERIFIED.md`.

## 8. How each part is built and checked

- Tests for each guard, and each guard mutation-verified: break the production line and confirm that
  exactly one test fails.
- The developer pane reviews each part's commits. Every finding is verified before it is fixed.
- Real-process-death proofs for the lease (§5.5) and for the credential handoff if path (a) is built.
- The dev stack is rebuilt after each part; both images bake their source.
- The docs change in the same commit as the behaviour.

## 9. What the operator is asked to do during the work

Nothing blocks the start. Two actions come later, and only the operator can perform them:

1. **Before F0:** the dedicated Codex sign-in (5.2, step 1). It is an interactive login.
2. **During D:** enter the vendor's published rates, or "not billed", for the build model's token types.

## 10. What the first review changed

The design was reviewed against the code on 2026-09-16 (`build/codex-review-991df93b.md`). Thirteen
findings, six of them high. Every claim quoted below was re-checked in the code before the design changed.

| Finding | Change |
|---|---|
| The agent could poison the stored sign-in through a write-back | 5.6: the container's copy is one-way; renewal runs in a trusted unit or not at all |
| A lease released on terminal results outlives a held build | 5.5: a fenced, bounded lease on agent activity |
| Adding fields to `binding()` supersedes every open gate | 6.3: a versioned binding; old preparations keep their exact hash |
| Stored artifacts keyed by item/generation lose or destroy bound bytes | 6.2: insert-only rows with their own immutable identity |
| An expected revision is not an authority fence for an automatic sweep | 6.4: the registration authority protocol plus the defaults revision |
| The credential return path had no durable protocol | 5.6: removed the return path; per-token scrubbing named |
| Declaring four codex buckets leaves `CACHE_WRITE` unpriced | 4: all five buckets declared |
| A nullable rate column silently reads as zero | 4: a typed rate, `wasNull`, backfill, `UNMETERED` ledger line |
| Subscription selection could change `/fix` and standalone runs | 5.4: every existing caller keeps API-key selection |
| Quota feedback has no rate-limit path today | 5.8: the whole path named, arch guard included |
| "The money cap does not act" overstated it | 5.7: cumulative caps still act; a subscription run adds zero |
| The sweep's trigger could never retry its own refusals | 6.5: preparation health kept separately, bounded backoff |
| Dispatch collapses every refusal into one reason | 6.6: a shared preflight and a structured refusal |
| The head read needs an item and falls back to REVIEWER | 3: a repository-scoped read, with the fallback stated |
