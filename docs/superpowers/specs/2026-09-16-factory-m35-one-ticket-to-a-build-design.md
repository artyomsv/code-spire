# Factory M3.5 — one ticket to a build

**Status:** design, 2026-09-16. **Parent:** the
[operator experience specification](2026-09-15-factory-operator-experience-design.md), §2.4 and §6(b),
with the operator's decisions 1B, 2A, 3A and 4B (§7 there). Part A and part E of that slice shipped with
M3. **New here:** part F, Codex subscription sign-in. The operator asked for it on 2026-09-16: *"It should
be available as an option but I would prefer to test both scenarios: with API tokens and with a
subscription."*

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
| 3 | F — Codex subscription | per-token spend for Codex | M/L | one dedicated Codex sign-in |
| 4 | C — system-composed preparation | the spec ticket, the plan ticket, the JSON, the form | M/L | nothing |
| 5 | P — live proof | — | S | two labels, two approvals |

B comes first because C and F read it. D comes before F because F's pool selection reuses D's priced
check to decide what an API-key run may start. C comes last: it prepares items without a person, so the
dispatch checks it relies on (D, F) must already tell the truth.

## 3. Part B — repository build defaults

**What the operator sees.** The repository Factory tab gains step 5, "How it builds":

- **Base branch** — text, with "Check" that shows the branch's current head through the repository's
  FACTORY account (the head read shipped in M3: `WorkPreparationResource.head`).
- **Harness** — a select of the configured agent images (`FactoryConfig.agentImage` keys).
- **Model** — a select of enabled models. After D, a model whose pricing is incomplete for the chosen
  harness is disabled and names the missing token types.
- **Pay with** — API key or Codex subscription. After F, the subscription choice is offered only for a
  harness that supports it and only when a sign-in exists.

**Storage.** A new table `repository_build_defaults` (repository id as key, base branch, harness, model,
billing, revision, updated by, updated at). It is configuration, not history. A preparation pins copies of
these values, and the gate binds the copies (ADR-045), so a later change never alters an open gate.

**API.** `GET` and `PUT /api/repositories/{id}/factory/build`, `spire-admin`, with an expected revision.
Refusals name the rule: `base_branch_blank`, `harness_unconfigured`, `model_unknown`,
`billing_unsupported`.

**Readiness.** `readiness()` in `factoryModel.ts` gains `build`. A repository is ready at five of five, and
the outcome line names the missing part.

**Manual form.** The prepared-task form fills its fields from the defaults.

## 4. Part D — pricing completeness

This follows the parent's §5, with decision 4B.

- **The "not billed" assertion.** `llm_model_rate` gains `billing` (`RATED` or `NOT_BILLED`). A `RATED` row
  keeps its rate above zero. A `NOT_BILLED` row has no rate. It prices as an asserted zero line, never as
  an unknown one, in the same way `UNMETERED` differs from `UNKNOWN` (`PricingMode.java`).
- **What a harness reports.** The codex adapter reports `INPUT`, `CACHED_INPUT`, `OUTPUT` and `REASONING`
  (parent §5, `CodexAdapter.java:276-279`). The orchestrator does not depend on `spire-harness` today
  (`spire-orchestrator/build.gradle.kts`), so the declaration must live where the orchestrator can read it.
  The plan chooses the place, and a test pins it to the adapter's own bucket mapping.
- **One predicate.** `isPriceable(model, harness)`: every reported bucket has a rate or a not-billed
  assertion. The three dispatch sites (`WorkRunAssembly.java:46`, `FixRunDispatcher.java:288`,
  `RunResource.java:225`) refuse with `model_pricing_incomplete` and the list of missing types.
- **Where the operator sees it.** Settings → LLM: each token type takes a rate or "The vendor does not bill
  this", and a model lists its gaps. Factory step 5 and the preparation select show the gap. The attention
  panel raises one row for each repository whose build defaults cannot be priced.
- **The system never types a price.** The operator enters the vendor's published rate.

**Acceptance** is the parent's §5 check.

## 5. Part F — Codex subscription sign-in

### 5.1 Why it does not exist today

ADR-031 carries subscription auth as an operator-owned mode for Codex. The operator's answer from OpenAI
support (recorded 2026-09-01) settles the terms question for this deployment. RUN-TOPOLOGY §1.1 measured a
containerised Codex answering on a subscription, and §10 even assumed Codex runs on one.

The build shipped only the API-key path:

- `harness_credential.api_key` holds one key (`V52__harness_credential.sql`).
- `CodexAdapter` pipes it into `codex login --with-api-key` (`CodexAdapter.java:108-131`).
- `RunCharges.record` prices every run per token.

No storage kind, injection, charging rule or screen exists for a sign-in file.

### 5.2 What the operator does

1. **Once, on their own machine:** sign in to Codex into a folder used only by the factory, for example
   `CODEX_HOME=<folder> codex login`. Do not use the everyday `~/.codex` folder. Two programs that share
   one sign-in can lock each other out when one of them refreshes the token (**not verified**; F0 measures
   it).
2. **Settings → Harness credentials → Add Codex subscription:** a label and the contents of `auth.json`.
   The screen never shows the contents again. This screen also lists and manages API keys, which today
   have no screen (`techdebt/spire-ui/4-3-three-factory-surfaces-still-have-no-screen.md`).
3. **Factory tab step 5:** Pay with → Codex subscription.

The screen recommends a ChatGPT seat used only by the factory (see 5.8).

### 5.3 F0 — measure before building

Use the pinned `spire-agent-codex` image (`@openai/codex@0.146.0`), the operator's dedicated sign-in and
one TEST prompt. Measure:

1. A container with only a copied `auth.json`, and no login step, runs `codex exec --json` to completion.
2. Whether a run rewrites `auth.json`, and when (`last_refresh`).
3. After a refresh, whether the previous refresh token still works.
4. What a usage-limit refusal looks like on the NDJSON stream and in the exit code, so that the pool can
   tell `rate_limited` from `rejected`.
5. Which usage buckets a subscription run reports.

The result chooses the refresh design in 5.5. The measurements go into EXECUTION-LAYER §3.3 with their
date and the CLI version.

### 5.4 Storage and selection

- **Migration.** `harness_credential` gains `auth_mode` (`API_KEY` or `SUBSCRIPTION`; existing rows become
  `API_KEY`). `api_key` is renamed `secret`. `base_url` may be empty for a subscription. `account_ref`
  holds the ChatGPT account id claim, never an e-mail address.
- **Upload check.** The file must be a JSON object with its tokens present and a readable account id. It
  is stored Tink-encrypted with the row id as AAD, as keys are today.
- **Selection.** `select(harness, billing)` picks only members of the requested mode. A `SUBSCRIPTION`
  member serves only a harness that declares subscription support (codex). The least-recently-exhausted
  order does not change.
- **One run per sign-in.** A `SUBSCRIPTION` member serves one run at a time. The lease clears on the
  run's terminal result, and the orphan watchdog clears it too. This removes the refresh race that ADR-031
  names.

### 5.5 Injection and refresh

- The worker passes the credential kind beside `HarnessInvocation.CREDENTIAL`. `CodexAdapter` writes the
  file to `$HOME/.codex/auth.json` with mode 0600 through a pipe, never through argv. Then it starts
  `codex exec` without a login step.
- **If F0 shows that a refresh replaces the token:** the worker reads the file back after the agent ends
  and returns it. The orchestrator stores it only when it parses, names the same account, and carries a
  newer refresh time. The container is untrusted, so a file that names another account is refused and
  logged without its contents. The runtime has no copy-out step today; the plan designs one.
- **If F0 shows that the old token keeps working:** there is no write-back. An expired sign-in surfaces as
  `rejected` with the words "Sign in again".

### 5.6 Money and limits

- A run on a `SUBSCRIPTION` member records its real token counts as `UNMETERED` lines, an asserted zero.
  Dispatch skips the model price check for it.
- Item caps still apply: runs, calls and wall clock (`WorkProgress.within`). The money cap does not act
  on these runs, as ADR-025 foresaw.
- A usage-limit refusal puts the member into `rate_limited_until`: the vendor's time if it gives one,
  otherwise a bounded default.
- Run detail says "Billed to: Codex subscription `<label>` · no per-token price".

### 5.7 The gate binds how the run is paid

The payment mode changes what an approval spends. The preparation therefore pins it and the binding
includes it: `WorkPreparation` gains `billing`, added through a wither, as CLAUDE.md requires for wire
records. A stored preparation without the field reads as `API_KEY`. The decision panel shows the mode.

### 5.8 Risks, stated plainly

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

- **Trigger.** An item sits at `spec / awaiting_input / specification_required`, its repository has build
  defaults, and its source can fetch the ticket. A scheduled sweep prepares it. The tracker and forge
  reads happen outside the item lock, as the manual resource does them today, and the write uses the
  expected revision. The manual form stays as an override.
- **Specification.** The ticket's title and body at that moment, stored encrypted in a new
  `work_item_artifact` table (item id, generation, kind `SPEC` or `PLAN`, ciphertext, SHA-256 of the
  stored bytes).
- **Plan.** `{"schemaVersion":1,"specificationSha256":…,"steps":[{"id":"step-1","instruction":"Implement
  the specification."}]}`: the schema the manual path already validates, so `WorkArtifacts.observe` keeps
  one rule set.
- **Where an artifact lives.** `WorkPreparation.Artifact` gains an origin, `TRACKER` or `STORED`, added
  through a wither. The binding includes it.
- **Base.** The head of the default base branch, read through the FACTORY account
  (`DiffSource.fetchBranchHead`).
- **Registered by** `system`, with the build-defaults revision recorded.
- **A readable copy on the ticket.** One tracker `COMMENT` effect through `WorkSourceEffects`, when the
  source has that capability. It names the specification digest, the one step, the base, the harness, the
  model, how the run is paid, and a link to the item. A source without `COMMENT` shows it on the dashboard
  only.
- **Refusals,** each shown on the list with its sentence: `ticket_body_empty`, `ticket_body_too_large`
  (today's bound, 48×1024 characters), `build_defaults_missing`, `branch_head_unconfirmed`,
  `model_pricing_incomplete` (D), `subscription_unavailable` (F).
- **A ticket edited after preparation.** The stored form stays what the gate binds. The detail page says
  "The ticket changed after it was prepared" and offers "Prepare again", which supersedes an open gate.
- **Evidence.** `WorkArtifacts.observe` reads `STORED` artifacts from the table and checks their digests.
  The decision panel's binding check does not change.

## 7. Part P — live proof on `spire-test`

1. Write two TEST tickets with acceptance criteria. Build defaults: harness `codex`, a priced model.
2. **Run 1:** Pay with → API key. Apply `spire:assisted`. Check that the plan gate shows the composed
   step. Approve. Check for one held build with a known cost.
3. **Run 2:** Pay with → Codex subscription. Same steps. Check for one held build with real token counts
   and an `UNMETERED` cost.
4. Count the human steps: write, label, approve. There must be no others.
5. Record the item, gate and run ids, the spend and the token counts in `docs/HISTORY.md` and an M3.5
   acceptance record. Update `docs/UNVERIFIED.md`.

## 8. How each part is built and checked

- Tests for each guard, and each guard mutation-verified: break the production line and confirm that
  exactly one test fails.
- The developer pane reviews each part's commits. Every finding is verified before it is fixed.
- The dev stack is rebuilt after each part; both images bake their source.
- The docs change in the same commit as the behaviour.

## 9. What the operator is asked to do during the work

Nothing blocks the start. Two actions come later, and only the operator can perform them:

1. **Before F0:** the dedicated Codex sign-in (5.2, step 1). It is an interactive login.
2. **During D:** enter the vendor's published rates, or "not billed", for the build model's token types.
