# Factory operator experience — findings analysis and improvement specification

**Date:** 2026-09-15. **Input:** the operator's ten findings after the first live item-linked build
(`artyomsv/spire-test` issue #36, run `run::github:artyomsv/spire-test:work-c77f4b21-…:1`), the
manual test script they followed, and the code on `feat/factory-m3-work-items`. **Scope:** the
Work items list and detail, the prepared-task registration form, the Approvals page, the sidebar,
the repository Factory tab, and the pricing gap that stopped the run.

**The product goal this specification must reach**, in the operator's words: *"I have one ticket,
I mark it for work and I receive a PR at the end, or for some projects the PR is even auto-merged."*
It is treated here as the target, not as a wish.

Every statement about current behaviour carries a `file:line` or a document section. Anything not
measured is marked **not verified**. Nothing here proposes removing a guard; each manual step is
named with the guard it protects and the way the system can satisfy that guard on its own.

---

## 0. Summary

| # | Finding | Root cause (short) | Coverage | Group |
|---|---|---|---|---|
| 1 | Work item detail is a mess | One flat card, sections in component-arrival order, raw enum values, two Refresh buttons, form always inline (`WorkItemDetail.tsx:27-56`) | M3 spec §9 lists content, not layout — **not covered** as a redesign | a (structure), b (mockups) |
| 2 | Back and forth to find ticket IDs | Spec and plan are *other* tickets typed as free text (`WorkItemPreparation.tsx:39-40`); the item's own title loads last (`WorkItemDetail.tsx:49-54`) | Consequence of 3; M4 FR-F18 removes the separate tickets — **partly covered** | b |
| 3 | Three issues for one task | M3 accepted a "manual tracker-artifact handoff" in which spec and plan must be tickets of the same source (`PREPARED-TASKS.md`, `WorkArtifacts.java:53-55`) | **Covered by M4** (FR-F18/F19: generated spec and plan, written back to the *same* ticket) | c, plus a bridge in b |
| 4 | Copying JSON into a plan ticket | The plan contract is a JSON body a human must author (`WorkArtifacts.java:37-47`; template at `WorkItemPreparation.tsx:44-45`) | **Covered by M4** for generated plans; the human path is **not covered** | b |
| 5 | Base commit, harness and model as free text | `WorkPreparationResource.Input` takes four strings (`:21-22`); the form renders six `<input>`s (`WorkItemPreparation.tsx:39-40`); no repository default exists — `/fix` has env defaults, items have none (`.env.example:41-48`, `FixRunDispatcher.java:272-293`) | **Not covered** | a (selects), b (defaults, base head) |
| 6 | 409 `single_step_plan_required` | One reason for three rules (`WorkArtifacts.java:39,43,47`); raw JSON error surfaced (`workPreparationApi.ts:22`); stale references kept after a failed register (`WorkItemPreparation.tsx:24-33,46`); plan SHA never shown (`:46`) | **Not covered** | a |
| 7 | No processing indicator | Buttons disable but never say what is happening; reads show a bare `<p>`; "Scan now" has no pending state at all (`SourceStep.tsx:25-29,46-47`) | WIDGETS.md asks for lock-out and success feedback, not for progress words — **not covered** | a |
| 8 | Approvals nav has no icon | `App.tsx:228` renders the link without the `ic` icon every other entry has (`:229-232`; WIDGETS.md row "Navigation") | **Not covered** | a (S) |
| 9 | Approvals page unusable | Cards show internal fields (generation, policy revision, ISO expiry, raw digest) and none of the evidence the gate binds (`Approvals.tsx:43-57`) | M3 spec §9 lists fields, not the decision's evidence — **not covered** | a (content), b (mockups) |
| 10 | API or subscription? | Measured: harness credential `factory-openai`, type `openai`, base URL `https://api.openai.com/v1` — an **API key**, billed per token (ADR-031; EXECUTION-LAYER §3.2) | Answered; the UI does not say it — **not covered** | a (S) |
| — | Too many manual steps | M3's accepted boundary is a human-prepared task (M3 spec §6.3); nothing resolves base, harness, model or artifacts for the operator; no verifier exists, so the PR can never open (`WorkPhaseCapability.java:11-16`) | M4 covers spec/plan/verify; the *defaults and composition* are **not covered** | b (new slice "M3.5"), c |

The measured stop: work item 36 ended at `verify | awaiting_input | run_usage_unknown` (dev DB,
2026-09-15). `gpt-5.5` carries rates for `INPUT,OUTPUT` only (dev DB `llm_model_rate`, measured
2026-09-15); the codex adapter also reports `CACHED_INPUT` and `REASONING`
(`CodexAdapter.java:276-279`), so the run's cost was unknown and the lifecycle refused to continue
(`WorkItemLifecycle.java:65`). The pre-dispatch check only asks for `INPUT` and `OUTPUT`
(`LlmModelPricer.java:78-85`, `LlmModelPricingValidator.java:21`). Section 5 owns this.

---

## 1. Findings, one by one

### Finding 1 — "Work item detail view is a mess"

**What is on screen.** `WorkItemDetail.tsx:27-56` renders one `card` with, in order: back link,
`<h2>{issueKey}</h2>` (a bare number on GitHub), tracker link, the journey block *or* a workflow
block, the policy block (raw mode table, `WorkItemPolicy.tsx:9-11`; limits as raw seconds and
millicents, `:14-17`), a one-line gate link, the admin action buttons, the full "Register a prepared
task" form inline (`WorkItemPreparation.tsx:35-50`), a second Refresh button (`:39`), applied
labels with raw actor ids and `origin.toLowerCase()` (`:41`), ignored labels, a history `<ol>` of
`"Workflow updated: <reason>"` lines (`:48`), and finally the ticket title and body (`:49-54`).

**Root cause.** The screen grew one section per slice (policy in slice 7, journey/preparation in
slice 8a, control in slice 9) and each slice appended a block; nothing ordered them by what a
reader needs first: *where is this item, what is it waiting for, what do I do now.* The design
brief for the screen (M3 spec §9: "Detail: tracker link, current phase, requested/effective
profile, ignored labels, clamp reason, gates and links to real runs/PR/review") lists content and
says nothing about hierarchy, so every block was placed as prose. ADR-045's consequence ("The UI
displays requested profile, ceiling, actual modes, limits and clamp reason") is met literally and
unreadably.

**Coverage.** Not covered by any later milestone. M4 adds more content (spec text, plan steps,
verification result, step summaries — FR-F31), which makes a redesign more urgent, not less.

**Why it is this way / guards.** No guard lives in the layout. The one structural rule worth
keeping is ADR-043's split: durable workflow facts come from the aggregate, tracker content is a
separate fetch that may fail without hiding history (`WorkItemDetail.tsx:50`). The redesign keeps
that: the ticket panel fails alone.

**Proposed change.** Section 3 gives the full brief. In one line: a status band with the one next
action, a phase strip with the current phase marked, then evidence cards (build, approval, policy,
labels, ticket), the history as a timeline, and the preparation form in a `SidePanel` opened by the
next-action button rather than always inline.

**Acceptance check.** A viewer opening item 36 reads, above the fold and without scrolling: the
ticket title, the phase (`verify`), the status in words ("Blocked: the run's usage could not be
priced"), and the single action the operator can take. `WorkItemDetail.test.tsx` (new) asserts the
order of landmarks by `aria-label`; a mutation that moves the ticket panel above the status band
fails exactly that test.

### Finding 2 — "I need to go forth and back to know what is the ticket ID"

**Root cause.** Two causes, both in the form at `WorkItemPreparation.tsx:39-40`: the specification
and plan are separate tickets the operator created outside the dashboard (test script steps 3–4),
and their keys are typed into free-text inputs with no lookup. The work item itself is shown by
its key only (`WorkItemDetail.tsx:30`); its title arrives at the bottom of the page after a second
fetch (`:49-54`), so even the item's identity is hard to confirm.

**Coverage.** Consequence of finding 3. Once the specification and the plan live with the ticket
(M4 FR-F18: "written back to the tracker as a comment") there is no second or third ID to remember.

**Guard protected.** The reference lookup (`WorkArtifacts.resolve`, `:19-26`) resolves a key to a
stable provider identity through the *selected source account* and refuses a ticket from another
project (`:53-55`). That is what stops a plan from an unrelated repository being bound to this
item. The guard is about identity and origin, not about who types the number.

**Proposed change.** (1) Show the ticket title in the page heading, with the key as a mono
subtitle. (2) Remove the two ticket fields from the human path by the design in finding 3; where a
reference to another ticket is still wanted, replace the free-text input with a search-as-you-type
lookup that calls `preparation/reference` and shows the resolved title before the key is accepted
(same shape as `AllowPerson` in `PeopleStep.tsx:19-59`: find, confirm, then act).

**Acceptance check.** The registration path in section 2 needs zero ticket IDs typed by a person.

### Finding 3 — "Why we created 3 issues for a work? One task/epic is not enough?"

**Root cause.** By design for M3. `PREPARED-TASKS.md`: "The specification and plan must be tickets
in the work item's registered source." `WorkArtifacts.fetch` enforces same type, origin and project
(`:53-55`) and reads the *body* of the referenced ticket (`:56-60`). The M3 design accepted this as
the boundary between M3 and M4: "implement the real phase state machine and manual
tracker-artifact handoff, then reuse M2 for one already specified build task" (M3 spec §6.3). The
three tickets are the price of proving the plan/build boundary without an M4 generator.

**Coverage.** **Covered by M4.** ROADMAP M4: "The `spec` phase — a vague ticket refined into
outcome, context and acceptance criteria, written back to the tracker." Design question 1 (ROADMAP
"Design questions — closed"): "A comment on the work item, plus the structured form on `work_item`
for the pipeline's own use." FR-F18 and FR-F19 generate both artifacts from the *one* ticket.

**Guard protected.** Pinned artifact digests. The plan gate binds the specification and plan
digests plus base branch, commit, harness and model (ADR-045; `WorkPreparation.binding()`,
`:31-40`), and every continuation re-reads the artifacts and refuses on a changed digest
(`WorkArtifacts.observe`, `:34-36`; `WorkItemTransitions.answer`, `:231`). The guard needs
*something with a digest that the gate can bind and the build can re-verify*. It does not need
that something to be a separate ticket.

**Proposed change.** Keep the digest guard; change what it hashes:

- **M4 (already designed):** the generated specification and plan are stored on the work item in
  their structured form (encrypted, under the existing Tink boundary — DATA-MODEL, ADR-043 last
  paragraph) and a readable copy is commented on the ticket. The digest is taken over the stored
  form. Editing the tracker comment does not change what the gate bound; re-generating does.
- **Bridge before M4 (section 2.4):** for a ticket that already contains its acceptance criteria,
  the system composes the preparation itself: specification := the ticket's current body (digest
  pinned exactly as today), plan := one step whose instruction is the ticket body or an operator
  sentence typed in the dashboard, stored in the same structured slot M4 will use. No second or
  third ticket exists. This is the path the operator's test actually exercised, minus the typing.

**Acceptance check.** `WorkItemJourneyIT` gains a case that admits a ticket, composes the
preparation from the ticket alone, opens the plan gate and dispatches one build after approval —
with exactly one tracker issue in the fixture. A mutation that skips the digest pin on the stored
plan fails the existing `artifacts_changed` case.

### Finding 4 — "It is not good design that I need to copy some JSON to a plan ticket"

**Root cause.** The plan is a machine contract — `{schemaVersion, specificationSha256, steps[1]}` —
validated at `WorkArtifacts.java:37-47`, and the UI hands the human that JSON to paste
(`WorkItemPreparation.tsx:44-45`). The `specificationSha256` field is what forces the operator to
"Read specification version" first, then copy, then edit a ticket, then come back (test script
step 8.2–8.3). The live 409 was a newline inside the instruction string, i.e. the human was doing
a serializer's job.

**Coverage.** Generated plans (M4 FR-F19) never show the JSON to a person. The human-authored path
is not covered.

**Guard protected.** `specificationSha256` binds the plan to the exact specification version it was
written against, so an edited spec cannot ride under an old plan; "exactly one step" bounds M3 to
the one-build case the run assembly supports (`WorkRunAssembly.java:33`).

**Proposed change.** The system composes the JSON. The human supplies at most one sentence — the
step instruction — in a dashboard field; the server fills `schemaVersion` and the SHA of the
specification it just pinned, validates, stores and pins. The JSON stays the internal wire form and
the audit record; it is never displayed except behind a "show the stored plan" disclosure.

**Acceptance check.** No "Single-step plan format" disclosure remains in the form. A plan whose
instruction contains a newline registers successfully.

### Finding 5 — "Base commit, harness as free text, models"

**Root cause.** `WorkPreparationResource.Input` (`:21-22`) takes `baseBranch`, `baseCommit`,
`harness` and `model` as strings; `WorkPreparation` only checks the commit is 40 hex characters
(`:26-28`). The form renders all four as `<input>` (`WorkItemPreparation.tsx:39-40`). Nothing in
the registry knows a repository's default branch head, allowed harnesses, or a default model for
items: the harness names are the keys of `FactoryConfig.agentImage()` (`FactoryConfig.java:24`),
the models are the LLM catalog (`llm_model`), and `/fix` gets its pair from two environment
variables that deliberately have no default (`.env.example:41-48`,
`FixRunDispatcher.refuseIfUnconfigured`, `:272-293`). Work items have no equivalent, so the human
types what `/fix` reads from config.

**Coverage.** Not covered by M4–M6. M4's plan phase needs the same coordinates and would inherit
the same form.

**Guards protected, and how each stays satisfied automatically.**

| Field | Guard | Automatic satisfaction |
|---|---|---|
| `baseCommit` | The gate binds a commit (ADR-045: "binds both artifact identities/digests and the base branch, commit, harness and model"), and the build clones at an explicit commit (FR-F2) — an approval is for a change against a known tree | The server resolves the head of the base branch through the repository's SCM client at registration, shows "`main` @ `a0f8a41` (head when prepared)", and pins that. A person may override with a full SHA in an "advanced" disclosure. The same check that today refuses a moved artifact can, later, tell the approver the base is behind head (**not verified**: needs a compare call per forge) |
| `baseBranch` | Same | Default to the repository's default branch (the `.codespire` rule already reads the target branch; the SCM model knows the default branch — **not verified** that `Repository` stores it) |
| `harness` | Must be a key of `agentImage` or dispatch refuses (`FactoryConfig.java:51`; `DispatchRequestParser`) | A `<select>` of the configured keys, served by a small read endpoint; default from the repository's build defaults (decision 3) |
| `model` | Must be priceable or dispatch refuses (`WorkRunAssembly.java:46`); the review model must differ from the build model (AUTONOMY §7) | A `<select>` from the LLM catalog, filtered to models with *complete* pricing for the chosen harness (section 5), default from the repository's build defaults |

**Proposed change.** (a) Immediately: selects for harness and model, base branch defaulted, commit
resolved by a "Use current head" action beside the field. (b) In the bridge slice: a fifth Factory
tab step "How it builds" (harness, model, agent image resolved from config, priceability shown),
after which the registration form has nothing left to ask except the optional instruction.

**Acceptance check.** Registering a task with defaults set requires no typed field. A mutation that
drops the head resolution leaves `baseCommit` empty and the server refuses with the existing "A
full base commit is required" (`WorkPreparation.java:27`).

### Finding 6 — 409 `{"reason":"single_step_plan_required"}`

**Root causes (both hit live).**

(a) `WorkArtifacts.observe` returns the same reason for three rules: not JSON (`:39`), wrong
schema version / SHA mismatch / step count (`:40-43`), and blank step id or instruction (`:45-47`).
`WorkPreparationResource.register` returns only `{reason}` (`:37`), and the client throws the raw
`status: body` string (`workPreparationApi.ts:22`), so the operator saw a JSON fragment and no
rule.

(b) `register()` keeps `references` on failure (`WorkItemPreparation.tsx:31` sets only the error),
so "Register these versions" (`:46`) stays visible with the version checked *before* the ticket
was edited; the server, reading fresh, answered `artifacts_changed` (`WorkArtifacts.java:34-36`).
The form shows the specification digest (`:43`) but never the plan digest (`:46`), so the operator
could not see that the plan version had moved.

**Coverage.** Not covered. With finding 4's change the invalid-JSON case disappears, but SHA
mismatch and changed-artifact remain real states and need words.

**Guards protected.** Both 409s are the digest guard working as designed: a plan that does not name
this specification version, and a reference that no longer matches the tracker. Neither is
removed.

**Proposed change.**

- `WorkArtifacts.Evidence` gains a `detail` naming the rule: `plan_not_json`,
  `plan_schema_version`, `plan_specification_mismatch`, `plan_step_count`, `plan_step_fields`.
  The resource returns `{reason, detail}`; `REASONS` in `WorkItems.tsx:24-90` gains one sentence
  per detail. (Reason stays the coarse contract other code keys on.)
- On any register failure the form clears `references`, keeps the typed values, and shows the
  sentence plus "Check the references again" as the only enabled action.
- Show both digests, shortened, beside each resolved reference, with the full value on copy.

**Acceptance check.** A plan body with a raw newline yields "The plan body is not valid JSON" on
screen; after `artifacts_changed` the Register button is absent until a fresh check succeeds
(`WorkItemPreparation.test.tsx` gains both cases; the existing "shows a failed registration without
announcing a continuation" case at `:63-68` is extended, not replaced).

### Finding 7 — "Not obvious that something is happening"

**Root cause.** Every async control in the factory surfaces uses one of two patterns: a `busy`
boolean that disables controls, or nothing. None changes the control's label to say what is
happening, and reads render a bare `<p>Loading…</p>` with no `aria-busy` region. The one
precedent that does it right is outside the factory: `RegisterPrDialog.tsx:203-204` renders
`{busy ? 'Registering…' : 'Register'}`. "Scan now" is the worst case: the request is fire-and-
forget: the button is disabled only while a form is open or the source is unavailable
(`SourceStep.tsx:46`), it is not locked while the request runs, and the only feedback is a notice
"Scan requested" after the call returns (`:25-29`). The request itself only sets
`scan_requested=true` (`WorkSourceScanner.java:25`); the sweep runs every 30 s (`:31`) and takes one
source per tick (`:35-36`), so the scan starts within about 30 s (test script step 6: "Wait about
30 seconds") and nothing on screen says so. Five minutes is the age at which an *unrequested*
source is rescanned (`:35`; `InstantUpdates.tsx:48`).

The stylesheet's own rule constrains the fix: "never an unbounded spinner" (`index.css:424-426`,
the `.responding` indicator), because a best-effort flag can stay true past a terminal failure.
The vocabulary that exists: `.pill.reviewing .glyph` pulses (`index.css:405-406`), `@keyframes
pulse`/`shimmer` (`:768-769`), `.responding` (`:427-433`).

**Coverage.** WIDGETS.md asks for "pending-save lock-out and success feedback" (header) and "keep
every control disabled during save" (row "Buttons") but not for a visible progress word. Not
covered.

**Proposed change.** One rule, section 4. In short: lock the form, change the primary label to the
present participle, announce with `role="status"`, and for anything that finishes later than the
HTTP call (scan, dispatch, run) show a bounded pending state with the last-observed time.

### Finding 8 — Approvals nav entry has no icon

**Root cause.** `App.tsx:228`:
`<a className={…} href="#/approvals">Approvals</a>` — no `<Icon className="ic" size={16} />`,
unlike Runs (`:224-227`) and Work items (`:229-232`). WIDGETS.md row "Navigation": "A visible icon
with `ic`, size 16, alongside the page name." The same commit that added the link skipped it.

**Proposed change.** `ClipboardCheck` from lucide-react, already the page's empty-state icon
(`Approvals.tsx:2,23`), so the rail and the page agree. Size S. Also consider the rail order: the
entry sits between Runs and Work items; the natural reading order is Work items → Approvals → Runs
(item, decision, execution).

**Acceptance check.** `App.routes.test.tsx` (or a new rail contract test) asserts every `nav a`
has a child with class `ic`; removing the icon from any entry fails it.

### Finding 9 — Approvals page "non-consumable"

**What is on screen.** `Approvals.tsx:43-57` per card: `issueKey · phase` as a heading, then
"Generation N · Policy revision N · OPEN", an ISO-8601 expiry, "Artifact or head: <64-hex>", "PR
review: Unavailable. Use the dashboard.", the tracker command, and a note textarea with two
identical `btn` buttons. Nothing says *what* is being approved: not the ticket title, not the plan
step, not the base, not the harness/model, not the caps that bound the build the approval will
start.

**Root cause.** The page renders the `Gate` record (`approvalsApi.ts:3-7`) field by field. The
gate binds a digest of the preparation (`WorkGate.artifactOf`, ADR-045), so the *evidence* the
approver needs is on the work item (`preparation`, `effectiveLimits`) and in the tracker (the
ticket, the plan instruction) — none of it is joined into the approvals read model. The M3 spec §9
row asked for "Open approvals with expiry, phase, artifact/head and decision note", which is
exactly what shipped, and it is not enough to decide.

**Coverage.** Not covered. M4 raises the stakes: a plan gate will front a multi-step plan (FR-F19)
and a land gate will front a PR with a review (FR-F17), so the approvals page must show plan steps
and review outcomes.

**Guards protected.** Dashboard answers bind `expectedVersion`, generation and artifact
(`WorkItemTransitions.answer`, `:206-258`); a stale answer is 409 `gate_changed`; the resolver is
the verified session, never the body (`WorkGateResource`, ADR-045). All of that stays. What
changes is only what the approver *sees* before clicking.

**Proposed change.** Section 3.3. In short: an approval is rendered as a decision request — what,
evidence, consequence, expiry, channels, decision — and the same card is reachable from the work
item detail so the plan can be approved without changing page.

**Acceptance check.** For an open plan gate the card shows the ticket title, the one step
instruction, base `branch @ short-sha`, harness/model, the run and cost caps formatted with
`formatCost`, and "expires in N h". A mutation that renders the raw digest instead of the
instruction fails `Approvals.test.tsx`.

### Finding 10 — "Was codex API or subscription used?"

**Measured (dev DB, 2026-09-15; credential columns not read):** the run's harness credential is
`factory-openai`, type `openai`, base URL `https://api.openai.com/v1`. That is an OpenAI API key.
Billing is per token, which is why the run has a priced part at all (9,521 millicents on the priced
lines) and why the unpriced `CACHED_INPUT`/`REASONING` lines could stop it. A subscription
(`auth.json`) run would have been `UNMETERED` and never blocked on price (EXECUTION-LAYER §3.2;
ADR-031: "API key is the default and only sanctioned mode, subscription auth is operator-owned").

**Why the operator had to ask.** Neither the run detail nor the work item names the credential
kind. **Not verified** whether `RunSpendCard`/`RunDefinitionCard` show the credential label.

**Proposed change.** On the run detail's definition card and on the approvals card: "Billed:
API key `factory-openai` (per token)" or "Subscription (no per-token price; call cap applies)".
The fact is already on `factory_run.harness_credential_id` (V52) and `harness_credential.type`.
Size S.

### The overall complaint — "too many manual steps, and some questionable"

The test script had eleven numbered steps plus two fix-ups. Section 2 maps each. The short answer:
M3 was accepted with a *human-prepared task* as its boundary (M3 spec §6.3, PREPARED-TASKS.md), so
every step between "label" and "approve" is a person doing what M4's generator will do, plus three
things nobody planned to automate (base head, harness/model defaults, complete pricing). The PR at
the end is blocked by the absence of a verifier: `WorkPhaseCapability.available` answers `false`
for everything but `build`, `deliver` and `review` (`:11-16`), and `deliver` requires a recorded
verification attempt (`WorkItemTransitions.validExecution`, `:169-180`, `case "deliver"`), which only M4 can
supply. The delivery code itself exists (`WorkDelivery.java:164-166` opens "Prepared work:
<key>" as draft or regular PR per the profile) and is unreachable in production today.

---

## 2. The end-to-end automation gap

### 2.1 Today's journey, step by step

"Manual" means a person did it in the test; "auto" means the system did it. The last column says
what makes the step automatic.

| # | Step | Today | Where | Becomes automatic in |
|---|---|---|---|---|
| 0 | Run worker running | Manual (dev: a Gradle process) | Test script step 0 | Ops: in the packaged stack it is a service (**not verified** that `deploy/compose.yml` includes it) — outside this spec |
| 1 | Repository Factory tab: source, person, ceiling, labels | Manual, once per repository | `RepositoryFactory.tsx` | Stays manual by design (Rule 1, AUTONOMY §3: the ceiling is operator-owned) |
| 2 | Create the label in the tracker | Manual (`gh label create`) | Test step 2 | Optional: "Create these labels in the tracker" from the presets form — needs a `WorkSource` label-create call that FR-F16 does not list (**not covered**; decision-free, size S/M per adapter) |
| 3 | Write the ticket | Manual, always | — | Never; this is the one ticket |
| 4 | Create a spec ticket, a plan ticket | Manual | Test steps 3–4 | **M4 FR-F18/F19**; bridge in 2.4 |
| 5 | Apply the label | Manual, the intended trigger | Test step 5 | Never; this is "I mark it for work" |
| 6 | Admission | Auto (webhook: instant; poll: ≤5 min) | `WorkItemLifecycle.reconcile`, `:22-38` | Already automatic; "Scan now" only shortens the wait |
| 7 | Spec phase | Stops: `awaiting_input / specification_required` (`:28-33`) | Manual: register | **M4** spec generation; bridge: ticket-as-spec |
| 8 | Plan phase | Manual JSON in a ticket | `WorkArtifacts.java:37-47` | **M4** plan generation; bridge: system-composed one step |
| 9 | Base branch/commit, harness, model | Manual, free text | `WorkItemPreparation.tsx:39-40` | **New**: repository build defaults + head resolution (2.4) |
| 10 | Check references, register | Manual, two clicks and a 409 | `:41-46` | **New**: registration is the system's own act after 7–9 |
| 11 | Plan gate | assisted: manual approve (by design); autonomous: auto | `WorkItemLifecycle.enter`, `:67-72` | Stays as the profile says (FR-F23/F25) |
| 12 | Build | Auto | `WorkRunAssembly.assemble` | Already automatic |
| 13 | Usage priced | Stopped: `run_usage_unknown` | `:65`; `LlmModelPricer.isPriceable` | **New**: pre-dispatch completeness check (section 5) |
| 14 | Verify | `capability_unavailable` | `WorkPhaseCapability.java:15` | **M4** verifier (recommend: first M4 slice) |
| 15 | Deliver: push + PR | Code exists, unreachable | `WorkDelivery.java:164-166`; `validExecution` requires verification | Unblocked by 14 |
| 16 | Review | Existing reviewer on the PR | FR-F21 (M2) | Already automatic once 15 happens |
| 17 | Land | assisted: `approve` (human merges or approves the PR review); autonomous: `auto_if_green` **unwired** | ROADMAP "Deliberately not built": needs risk scorer, protected paths, kill switch, and dismissal-rate evidence from running at `land: approve` | Not scheduled; price of admission stated |

Steps 4, 7, 8, 9, 10 and 13 are the "questionable" manual steps. Steps 1, 3, 5 and (for assisted)
11 and 17 are the human decisions the design wants.

### 2.2 The target journey per preset

From AUTONOMY §2 and `presets.ts`. Human touches are in **bold**.

| Preset | Journey after **write the ticket, add the label** |
|---|---|
| **suggest** (`spire:suggest`) | admit → spec generated and commented on the ticket → plan generated and commented → *stops before build* (`build: off`). The ticket now carries a specification and a plan a person can act on. Zero sandbox runs. |
| **assisted** (`spire:assisted`) | admit → spec → plan → **approve the plan** (dashboard, or `/approve …` on the ticket) → build → verify (repository back-pressure; `unverified` is a stated outcome) → draft PR → reviewer reviews → **a person merges** (`land: approve`; approving the PR review answers the land gate where the forge supports it, ADR-045). |
| **autonomous** (`spire:auto`) | admit → spec → plan → build → verify → PR → review → `land: auto_if_green` — **stays unwired** until the ROADMAP's price of admission is paid; until then behaves as `land: approve`. |

"PR at the end" is the assisted and autonomous exit. "Auto-merged for some projects" is the
autonomous `land` rung, and the ROADMAP is explicit that it is deliberately not built yet; the
recommendation is to leave that decision where it is and not fold it into this work.

### 2.3 The minimum set of changes for "one ticket, one label, PR at the end"

1. **The system prepares the task** (removes steps 4, 7–10): specification and single-step plan
   composed and stored by the system, base head resolved, harness/model from repository defaults,
   registration performed by the lifecycle, not by a form. The plan gate still opens for
   `assisted`; the approver sees the composed plan.
2. **Complete pricing before dispatch** (removes the step-13 stop): section 5.
3. **A verifier** (removes the step-14 stop and opens step 15): M4's verify phase, at minimum
   "run the repository-declared commands, or report `unverified` when none are declared" —
   FR-F20 already defines `unverified` as the honest outcome.
4. **Nothing else.** Delivery, review and the land gate exist.

Items 1 and 2 need no model call and no new phase executor. Item 3 is M4 work.

### 2.4 Where it fits — recommendation

**Recommendation: one new slice before M4, called "M3.5 — One ticket to a build", followed by M4
with verify as its first slice.**

M3.5 contents:

| Part | What | Size |
|---|---|---|
| A | Group (a) fixes from section 6 (icon, indicators, 409 detail, stale references, selects) | S |
| B | Repository build defaults: Factory tab step 5 "How it builds" (harness, model, resolved agent image, pricing completeness), readiness includes it | M |
| C | System-composed preparation: on `specification_required`, if the repository has build defaults, the lifecycle pins the ticket body as the specification, composes the one-step plan (instruction := ticket body, or the operator's sentence if one is registered), resolves base head, stores the preparation and enters the plan phase. The plan gate shows the composed plan. | M/L |
| D | Pricing completeness: harness-reported buckets, `isPriceable(model, harness)`, refusal names the types, Settings → LLM shows the gap, Factory tab shows it | M |
| E | Work item detail and Approvals redesign from mockups (section 3) | M/L |

Why a separate slice and not "fold into M4":

- M4's generator writes into the same slot C creates (ROADMAP design question 1: "the structured
  form on `work_item`"). Building the slot, the defaults and the pinning first means M4 only
  replaces *how the text is produced*. Doing it inside M4 couples a UI/policy change to two model
  calls and a verifier.
- C is exactly the path the operator's test walked, minus the typing. It can be proved live on
  `spire-test` with one build and no LLM planning spend, which de-risks the M4 exit criterion
  ("three or more steps, then three branches that each verify, then one pull request").
- The operator raised these findings *now*; a slice with a visible exit ("label → approve →
  build, with no other human step") answers them before M4's larger work starts.

Why not "before merge of the M3 branch": B–E change the lifecycle and the read models; the M3
branch is in final review and its acceptance record is measured. Only part A belongs there.

Why verify first inside M4: it is the only missing piece between a built commit and the PR
(`validExecution`, `case "deliver"`). Spec and plan generation remove typing; verify removes the
wall.

---

## 3. UI redesign requirements

**Mockups (2026-09-15):** three versions of each screen below — Work items list (A triage table, B queue by group, C board by phase), Work item detail (A two columns, B phase steps, C tabs) and Approvals (A inbox, B card stack, C inside Work items) — built from the real item #36 data, published as the private artifact "Work Items & Approvals Redesign". **Chosen by the operator on 2026-09-16: list A (triage table), detail B (phase steps), approvals C (inside Work items, decision in a side panel).** Part E builds those three.

Vocabulary is WIDGETS.md's: `content`, `card`, `prov-*`, `chips`/`chip`, `pill`, `wh-empty`,
`attn`, `SidePanel` (`busy`, `tabs`, `wide`), `FactoryStep` (`done | missing | editing`),
`PhaseStrip`/`PhaseLegend`, `SettingField`, `Tooltip`, `CopyField`, `ConfirmDialog`,
`ActorPicker`/`actorLabel`. Money through `formatCost` (`money.ts`), times through
`formatEventTime` (`format.ts`). New components stay under 250 lines and eight state hooks (M3
spec §9). These are briefs for mockups; no layout is chosen here.

### 3.1 Work items — list

**Information, in priority order.**

1. *What needs a person now.* A count and a band above the table (`attn`) for items in
   `waiting_approval`, `awaiting_input` and `suspended` — the states with a next action.
2. Per row: ticket key **and title** (title requires a bounded tracker read the summary does not
   carry today — `WorkItemSummary`, `api.ts:5-19`; ADR-043 forbids a mirror, so either the list
   endpoint fetches titles with the same 20 s bound `WorkArtifacts` uses, or the row shows the key
   and loads the title lazily — decision for the implementer, **not verified** which is cheaper).
3. Where it is: the eight-phase `PhaseStrip` with the current phase marked, plus the status
   `pill` and the reason sentence (`workReason`) as the row's second line.
4. Profile as "name vN" and a `chip warn` "clamped" when `policy_clamped`.
5. Repository, updated (relative), cost so far (from `progress.costMillicents` — on the detail
   today, add to the summary), and whether a run is live.

**Actions.** Filter by "needs me / running / waiting / stopped / all" (chips, not a `<select>` of
ten raw statuses as at `WorkItems.tsx:110-112`); open; Refresh (busy-aware) until the list goes
live. Live: the M3 spec §9 promises a revision for bounded polling; a `useLiveWorkItems` on the
pattern of `useLiveRuns` removes Refresh entirely.

**States.** Loading: `aria-busy` table region with the header rendered and a `prov-sub` line
"Loading work items…". Empty: existing `wh-empty` (`WorkItems.tsx:114-118`). Filtered empty:
existing. Error: `prov-error` with retry. Stale: when the last successful read is older than the
poll interval, a `chip warn` "not updated since HH:MM" beside the title (never silent).
Processing: Refresh button label "Refreshing…" and disabled.

**Layout directions.**

- **A — Triage table.** Attention band on top, one table, chips as filters, phase strip inline.
  Closest to `RepositoryRegistryPage`; lowest risk.
- **B — Grouped list.** Four groups in reading order: Needs you, Running, Waiting, Finished.
  Each group a `card` with rows; empty groups collapse to a one-line note. Reads as a queue.
- **C — Board by phase.** Eight columns from `intake` to `land`, cards per item. Honest about
  where work sits, but eight columns are wide and most will be empty for months; only worth a
  mockup if the operator runs many items at once.

### 3.2 Work item — detail

**Information, in priority order.**

1. *Identity.* Ticket title (h2), key and repository as mono subtitle, "Open in tracker" link,
   tracker status chip.
2. *Status band.* Phase strip with the current phase marked; status pill; the reason sentence;
   and **the one next action** as the primary `btn`: "Approve the plan" (opens the approval card
   in a `SidePanel`), "Prepare the task" (opens the preparation panel), "Recheck and resume",
   "Re-admit", "Open the run", or "Nothing to do — waiting for <phase>". Secondary actions
   (`btn-ghost`) beside it. This replaces the scattered buttons at `WorkItemDetail.tsx:37-39,75-81`.
3. *Evidence cards*, only those with content:
   - **Build**: run link (`shortRunId`), built commit, pushed/held state, PR link with draft
     state, review link, harness/model, credential kind (finding 10), cost so far, wall time.
   - **Prepared task**: specification (title, digest short), plan step instruction in full,
     base `branch @ sha`, harness/model, registered by whom and when. "Show stored plan"
     disclosure for the JSON.
   - **Approval**: the open gate (or last decision) rendered with the same component as the
     Approvals page — one implementation, two places.
   - **Policy**: selected profile vs ceiling, `PhaseStrip` of effective modes with a `PhaseLegend`,
     clamp note when clamped, limits formatted (money via `formatCost`, durations as "2 h",
     counts as "3 of 5 runs used"), protected paths in a disclosure. Replaces the raw table at
     `WorkItemPolicy.tsx:9-17`.
   - **Labels**: applied labels as `chip` with the person (`actorLabel`, not the raw id at
     `WorkItemDetail.tsx:41`) and origin in words; ignored labels with their reason sentence.
4. *Timeline.* Every event with a relative time, the phase, the milestone in words, the resolver
   for gate decisions, and the operator note for control actions. Replaces the `<ol>` at `:48`.
5. *Ticket.* Title, status, body, in a collapsible card that fails alone (`:50`).

**Actions.** Next action (above); Refresh until live; the preparation form and the approval
decision open in `SidePanel` (busy-locked `fieldset`), never inline.

**States.** Loading: skeleton of the status band. Error: item error vs tracker error kept separate
(as today). Processing: the next-action button carries the participle label and the panel locks.
Stale: after a mutation the page re-reads; if the revision did not change, say so ("No change
recorded yet — the tracker is being re-read") instead of silently showing the same screen.
Suspended: the resume note field is required and says why (`:76-78`).

**Layout directions.**

- **A — Two columns.** Left: status band, evidence cards, timeline. Right, sticky: policy, labels,
  ticket. Good for wide screens; stacks to one column under 900 px.
- **B — Phase steps.** Reuse `FactoryStep`'s numbered vertical list for the eight phases:
  `done` for completed phases, `editing` for the current one (with its evidence and action
  inside), `missing` for phases the profile has off. The screen then *is* the journey. Strong
  fit with the Factory tab the operator already accepted.
- **C — Tabs.** Overview (status band + build + approval), Policy, History, Ticket. Least
  scrolling; hides the timeline behind a click.

### 3.3 Approvals

**Information, in priority order — per decision.**

1. *What.* "Approve the **plan** for **<ticket title>**" — phase as a verb phrase, ticket title,
   key, repository, requested profile.
2. *Evidence — what you are approving.* For a plan gate: the specification (title and a bounded
   excerpt), the step instruction in full, base `branch @ sha`, harness/model, credential kind.
   For a land gate (M4+): PR link, review verdict and open blockers, verification outcome.
3. *Consequence.* "Approving starts **one** build, capped at **$X** and **N min**" from
   `effectiveLimits` (`maxCostMillicents`, `maxWallClockSeconds`, `maxRunsPerItem`), formatted.
   Never an estimate of actual spend.
4. *Time.* Opened (relative), expires in (relative, `chip warn` under one hour), by whom it was
   requested (the label applier via `actorLabel`).
5. *Channels.* The tracker command in a `CopyField` with one sentence ("Allowed people can answer
   on the ticket with this"); PR-review availability only when the phase is `land`, otherwise
   omitted (today's "PR review: Unavailable. Use the dashboard." at `Approvals.tsx:48` reads as a
   fault).
6. *Decision.* Note field, **Approve** (`btn`) and **Reject** (`btn-ghost danger`), locked
   during submit with "Approving…"/"Rejecting…".

**Actions.** Approve / Reject; open the work item; "Show history" as a tab, not a checkbox
(`:19`).

**States.** Empty: existing `wh-empty`. Loading: `aria-busy` list. Deciding: card locked, label
changes. Decided: the card stays for one render with "Approved by you just now → building" and a
link, then leaves the open list. Conflict: 409 renders inline on the card as "This decision changed
(expired, superseded, or already answered). Reload to see the current one" with a Reload action —
the `approvalsApi.ts:19-20` sentences are right, the placement is not. Expired: shown in history
with the re-admit link the attention row already carries (`WorkAttentionRows.java:33-35`).

**Layout directions.**

- **A — Inbox.** Open decisions as a compact list on the left (title, phase, expires in); the
  selected decision on the right in a `wide` panel with all evidence. History is a tab of the
  list. Scales to many decisions.
- **B — Card stack.** One full-width card per decision, evidence expanded, decision controls at
  the bottom of each. Simplest; right while decisions are few.
- **C — Fold into Work items.** Approvals becomes the "Needs you" filter of the work items list,
  and the decision card lives on the detail; the rail entry stays as a shortcut to that filter.
  One screen fewer; the attention bell already links to `/approvals` (`WorkAttentionRows.java:35`),
  which would then redirect.

### 3.4 Prepared-task registration (until 2.4 part C makes it rare)

A `SidePanel` titled "Prepare the task", opened from the detail's next action. Fields, top to
bottom: specification (default "this ticket", with a lookup for another ticket), instruction (one
textarea, defaulted to the ticket body), base branch (select, default branch selected), base commit
(resolved head shown as `chip mono`, "Use current head" and an advanced override), harness
(select), model (select, incomplete pricing shown disabled with the missing types), and the
resolved digests. One primary action "Prepare" that checks and registers in one server call; the
two-step "Check → Register" exists today only because the client must send digests it has seen
(`WorkPreparationResource.Input`), and a server-side compose removes that.

---

## 4. Loading and processing indicator audit

| Surface | Action | Shows progress today | Evidence |
|---|---|---|---|
| Work items list | Initial load | `<p>Loading work items…</p>`, no `aria-busy` | `WorkItems.tsx:113` |
| Work items list | Refresh | No — button stays "Refresh work items", not disabled | `:109` |
| Work items list | Status filter change | Same bare `<p>` while reloading | `:101-105,113` |
| Work item detail | Initial load | `<p>Loading work item…</p>`; tracker `<p>Loading current tracker content…</p>` | `WorkItemDetail.tsx:29,51` |
| Work item detail | Refresh workflow | No | `:39` |
| Work item detail | Recheck and resume / Re-admit | Buttons disabled while busy; label unchanged | `:78,80` |
| Prepared task | Read specification version / Check references | Buttons disabled (`busy !== null`); label unchanged; no status text | `WorkItemPreparation.tsx:41-42` |
| Prepared task | Register these versions | `fieldset` disabled while saving; label unchanged | `:38,46` |
| Approvals | Initial load / history toggle | `<p>Loading approvals…</p>` | `Approvals.tsx:22` |
| Approvals | Refresh | No | `:20` |
| Approvals | Approve / Reject | `fieldset` disabled; labels unchanged | `:52-54` |
| Factory tab | Initial load | `<p class="factory-note">Loading factory setup…</p>` | `RepositoryFactory.tsx:45` |
| Factory tab | Scan now | **No** — disabled only while a form is open or the source is unavailable, not locked during the request, no pending state; notice "Scan requested" after the call; the scan starts on the next 30 s tick with nothing on screen | `SourceStep.tsx:25-29,46-47`; `WorkSourceScanner.java:25,31,35` |
| Factory tab | Add / Edit source (save) | `fieldset` disabled (**not verified** for `SourceForms.tsx`, by convention) | WIDGETS.md row "Buttons" |
| Factory tab | Find person | `fieldset` disabled; label unchanged | `PeopleStep.tsx:42,46` |
| Factory tab | Allow person | Same | `:56` |
| Factory tab | Remove person | Button disabled | `:78` |
| Factory tab | Save ceiling / Save labels | `fieldset` disabled; label unchanged | `PolicySteps.tsx:47,53,94,99` |
| Factory tab | Apply presets | `fieldset` disabled; label unchanged; on failure `reload()` | `PresetForm.tsx:32,48` |
| Factory tab | Turn on instant updates | Button disabled; label unchanged | `InstantUpdates.tsx:49` |
| Runs list | Load | "Loading runs…" | `Runs.tsx:177-179` |
| Register PR dialog (reference) | Register | **Yes**: `busy ? 'Registering…' : 'Register'` | `RegisterPrDialog.tsx:203-204` |

**One rule for all of them.**

1. *Lock.* Every mutation runs inside a `fieldset disabled` (`SidePanel` or `form-lock`), Cancel
   included (WIDGETS.md already says so).
2. *Say it.* The primary control's label becomes the present participle with an ellipsis for the
   duration — "Preparing…", "Approving…", "Scanning…", "Refreshing…" — and a `role="status"`
   element carries the same words for assistive tech. Static text, never an unbounded spinner
   (`index.css:424-426`).
3. *Reads.* A region being fetched sets `aria-busy="true"` and shows its frame (headers, card
   titles) with a `prov-sub` line "Loading …" — not an empty page.
4. *Later-than-the-call work.* When the result arrives after the HTTP response (scan, dispatch,
   run, publication), show a bounded pending state with the last observed fact and its time:
   "Scan requested 12 s ago · last scan finished 14:02" (needs the source's last-scan time —
   **not verified** that `WorkSource` exposes it) or "Build queued · waiting for a run worker".
   The pending state clears on the next successful read; it never claims completion.
5. *Finish.* On success show the returned record and a `role="status"` notice
   (`RepositoryFactory.tsx:52` is the precedent); on failure keep the typed values and name the
   next action.

A contract test on the pattern of `settingsTables.contract.test.ts` can enforce 1 and 2 for every
file under `components/work-items` and `components/repositories/factory`: every `onClick` that
awaits an API function must sit in a component with a `busy` state that reaches a label.

---

## 5. The pricing gap

**What happened.** `WorkRunAssembly.assemble` asks `pricer.isPriceable(model)` (`:46`), which is
true when the model is `METERED` and has rates for `INPUT` and `OUTPUT`
(`LlmModelPricer.java:78-85`; `REQUIRED_RATES`, `LlmModelPricingValidator.java:21`). The codex
adapter reports up to five buckets (`TokenBucket.java:27-40`), and for this run it reported
`CACHED_INPUT` 60,288 and `REASONING` 22. The pricer turns a bucket with no rate into an unknown
line (`line()`, `:87-93`), a run with any unknown line has an unknown cost (`RunCost.plus`,
`:81-89`), the result carried `usageKnown=false`, and `WorkItemLifecycle.enter` refused the next
phase (`:65`). Everything downstream behaved correctly; the defect is that the check at `:46`
asked a weaker question than the harness can answer.

**Where to detect it, in order of earliness — none of them invents a price.**

1. **Model save (Settings → LLM).** `LlmModelPricingValidator.validate` keeps requiring
   `INPUT` and `OUTPUT`. Add a *warning* in the save response and on the model row: "No rate for
   `CACHED_INPUT`, `CACHE_WRITE`, `REASONING`. A call that reports these will be unpriced." The
   operator enters the vendor's published rate for each, or asserts per type "the vendor does not
   bill this" — an explicit operator assertion stored as such, distinguishable from an unentered
   rate exactly as `UNMETERED` is distinguishable from zero (`:79-86`). Decision 4.
2. **Harness declares what it reports.** `HarnessCapabilities` (`HarnessCapabilities.java:4-5`)
   gains `Set<TokenBucket> reportedBuckets`; codex declares `INPUT, CACHED_INPUT, OUTPUT,
   REASONING`. `isPriceable(model)` becomes `isPriceable(model, harness)`: every reported bucket
   has a rate or a not-billed assertion. Same predicate at the three dispatch sites
   (`WorkRunAssembly.java:46`, `FixRunDispatcher.java:288`, `RunResource.java:225`), refusing with
   `model_pricing_incomplete` and the list of missing types. `TokenBucketMatchesLedgerDimensionsTest`
   already pins the bucket/type mapping.
3. **Registration and the Factory tab.** The model select lists incomplete models disabled with
   the missing types; the Factory tab's "How it builds" step is `missing` until the pair is
   complete, so `FactoryOutcome` reads "Not ready — the build model has no rate for CACHED_INPUT"
   before any label is applied.
4. **Attention.** `AttentionQueries.java:127-134` already raises a row when the reviewer's default
   model is unpriceable; the same row for each repository's build defaults.
5. **After the fact (unchanged).** A run that still reports an undeclared bucket stays
   `run_usage_unknown` — the fail-closed rule is the last line, not the first.

**Acceptance check.** With `gpt-5.5` priced for `INPUT,OUTPUT` only: the model is disabled in the
select with "no rate for CACHED_INPUT, REASONING"; a test dispatch through `WorkRunAssembly`
refuses with `model_pricing_incomplete` naming both; after the operator enters both rates (from
the vendor's page, never typed by the system) the same dispatch proceeds. A mutation that drops
`REASONING` from codex's declared buckets makes the first assertion fail.

---

## 6. Sequencing

### (a) Small fixes on the M3 branch before merge

| Change | Size | Depends on |
|---|---|---|
| Approvals rail icon (`App.tsx:228`) and rail order | S | — |
| Progress labels and `role="status"` on every factory-surface mutation; `aria-busy` on reads; "Scan now" pending state (bounded, with last-observed time if available) | S/M | — |
| `Evidence.detail` sub-reasons for `single_step_plan_required`; `{reason, detail}` on the resource; sentences in `REASONS`; clear stale references on any register failure; show both digests | S | — |
| Harness `<select>` (small read endpoint over `FactoryConfig.agentImage()` keys) and model `<select>` from the LLM catalog read the LLM settings screen already uses (**not verified** which endpoint) | S | — |
| Base branch defaulted; "Use current head" resolving the branch head through the repository's SCM client | S/M | SCM client exposes a branch-head read (**not verified**) |
| Credential kind ("API key / subscription") on run detail and approvals | S | — |
| Ticket title in the detail heading; `actorLabel` for label appliers | S | — |

None changes a lifecycle rule or a read model beyond adding fields; all are mutation-verifiable in
the existing UI suites.

### (b) The bridge slice "M3.5 — One ticket to a build" (before M4, alongside its planning)

| Part | Size | Depends on |
|---|---|---|
| B — Repository build defaults (Factory tab step 5; registry rows; readiness) | M | (a) selects |
| D — Pricing completeness (buckets on capabilities, `isPriceable(model, harness)`, not-billed assertion, refusal reason, Settings → LLM warning, attention row) | M | Decision 4 |
| C — System-composed preparation (stored structured spec/plan slot, ticket-as-spec, one-step compose, head resolution, lifecycle enters plan phase on its own) | M/L | B, D, Decisions 1–2 |
| E — Work items list/detail and Approvals redesign from mockups; approval card shared between pages; live updates | M/L | mockups from section 3 |
| Live proof on `spire-test`: label → (approve) → build, no other human step; one run's spend | S | run worker, priced model |

Exit criterion: on a ticket that carries its own acceptance criteria, in a repository with build
defaults, applying `spire:assisted` produces an open plan gate whose card shows the composed step
and the caps; approving it produces one held build. The only human steps are writing the ticket,
applying the label and approving. `suggest` stops with the composed plan visible;
`autonomous` builds without the gate.

### (c) Already covered by M4–M6 — note only

| Item | Where | Note to add |
|---|---|---|
| Generated specification and plan from one ticket (findings 3, 4) | M4, FR-F18/F19 | Write into the slot M3.5 part C creates; keep the tracker comment as the human copy; the digest guard hashes the stored form |
| Verify, and therefore the PR at the end | M4, FR-F20 | **Recommend it is M4's first slice**; `unverified` when the repository declares nothing |
| Step continuity | M4, FR-F31 | The detail's timeline (3.2 §4) is where step summaries render |
| Auto-merge | ROADMAP "Deliberately not built" | Leave the price of admission as written; the operator's "for some projects" is `land: auto_if_green` per profile, already the design |
| Harness credential screen, cancel/steer, dispatch resolution | `techdebt/spire-ui/4-3-three-factory-surfaces-still-have-no-screen.md` | Unchanged; finding 10's credential-kind display touches the same data |
| Cost per merged PR, autonomy rate | M6, FR-F26 | The list's "cost so far" column is the per-item precursor |

---

## 7. Decisions for the operator

Five decisions, two options each. The operator decided all five on 2026-09-15; each heading records the choice.

**1. Where a human-written specification and plan live when nothing generated them.** **Decided by the operator on 2026-09-15: B.**
*Context:* M3 requires them to be tickets (`PREPARED-TASKS.md`); M4 will store generated ones in a
structured slot on the work item and comment a copy on the ticket (ROADMAP design question 1).
*A:* keep them in the tracker — the ticket body is the spec, a comment is the plan; the system
pins digests of those. *B:* the system stores them (encrypted) in the same slot M4 will use, and
writes a readable copy to the ticket as a comment; digests are over the stored form.
*Recommendation:* **B.** One storage shape for M3.5 and M4; a comment edit cannot break a pinned
digest; the tracker still shows what was approved. ADR-043's "no mirror" is about the *ticket*
(title, body, status), which stays unmirrored; this is the pipeline's own artifact.

**2. When the base commit is pinned.** **Decided by the operator on 2026-09-15: A.**
*Context:* the gate binds the commit (ADR-045). *A:* at preparation — the head of the base branch
when the task is prepared; the approver sees the exact tree; a later push to `main` does not
change what was approved. *B:* at dispatch — the head when the build starts; fresher, but the
approver approved a plan against a tree that may differ.
*Recommendation:* **A**, with "the base is behind head" shown on the card when a compare call is
available (**not verified** per forge), and an explicit "Update base" that supersedes the gate.

**3. Where build defaults (harness, model) live.** **Decided by the operator on 2026-09-15: A.**
*A:* per repository, as Factory tab step 5. *B:* per profile, beside the caps.
*Recommendation:* **A.** The agent image and the credential pool are deployment/repository facts;
a profile is about autonomy and limits (FR-F23). A profile may later *narrow* (e.g. forbid a
harness), never choose (NFR-F2).

**4. How incomplete pricing is closed.** **Decided by the operator on 2026-09-15: B.**
*Context:* section 5; the system never invents a price. *A:* every bucket the harness reports must
have a rate above zero, or dispatch refuses. *B:* per token type the operator may assert "the
vendor does not bill this", stored as an explicit assertion distinct from an unentered rate, and
dispatch accepts it.
*Recommendation:* **B.** Some vendors genuinely bill nothing for a bucket; forcing a fake positive
rate is the invented price the rule forbids, and `UNMETERED` already sets the precedent for an
operator-owned zero.

**5. M4's internal order.** **Decided by the operator on 2026-09-15: A.**
*A:* verify first, then spec and plan generation. *B:* as ROADMAP lists — spec, plan, verify.
*Recommendation:* **A.** Verify is the only thing between a held build and the PR
(`validExecution`, `case "deliver"`); generation removes typing that M3.5 already makes optional.
"PR at the end" arrives one slice earlier.

---

## Appendix — evidence index

| Claim | Where measured |
|---|---|
| Item 36 stopped at `verify / awaiting_input / run_usage_unknown` | dev DB `orchestrator.work_item`, 2026-09-15 |
| `gpt-5.5` and `gpt-5.5-pro` are `METERED` with `INPUT,OUTPUT` only | dev DB `llm_model` ⋈ `llm_model_rate`, 2026-09-15 |
| Codex reports `CACHED_INPUT` and `REASONING` | `spire-harness-codex/.../CodexAdapter.java:276-279` |
| Priceable means `INPUT`+`OUTPUT` | `LlmModelPricer.java:78-85`; `LlmModelPricingValidator.java:21` |
| Pre-dispatch check sites | `WorkRunAssembly.java:46`; `FixRunDispatcher.java:288`; `RunResource.java:225` |
| Unknown usage refuses the next phase | `WorkItemLifecycle.java:65` |
| Spec phase waits for input under `spec: auto` | `WorkItemLifecycle.java:27-33` |
| Only build/deliver/review can be available; deliver needs verification | `WorkPhaseCapability.java:11-16`; `WorkItemTransitions.validExecution` |
| Delivery opens "Prepared work: <key>" as draft or regular PR | `WorkDelivery.java:164-166` |
| Plan rules share one reason | `WorkArtifacts.java:39,43,47` |
| Preparation input is four free strings | `WorkPreparationResource.java:21-22`; `WorkPreparation.java:23-28` |
| `/fix` defaults are env-only, no item equivalent | `.env.example:41-48`; `FixRunDispatcher.java:272-293` |
| Gates reach the attention panel | `WorkAttentionRows.java:26-37` |
| Approvals nav has no icon | `App.tsx:228` vs `:224-232` |
| Auto-merge deliberately unbuilt | `docs/factory/ROADMAP.md` "Deliberately not built" |
| M3's manual-handoff boundary | `docs/superpowers/specs/2026-09-12-factory-m3-work-items-design.md` §6.3; `docs/factory/PREPARED-TASKS.md` |
| Spec written back to the tracker as a comment; structured form on the work item | `docs/factory/ROADMAP.md` "Design questions — closed", item 1; PRD FR-F18 |
| Credential kind for the live run | dev DB `harness_credential` (label, type, base_url only), reported in the findings brief |
