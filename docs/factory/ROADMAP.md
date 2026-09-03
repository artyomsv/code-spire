# Software Factory — Roadmap

Build order for the factory. The reviewer's roadmap is [`../ROADMAP.md`](../ROADMAP.md) and is
unchanged; this file owns the factory's sequencing only.

Two rules, borrowed from what works in the reviewer's roadmap:

1. **A milestone is a vertical slice.** It ends at something runnable and observable, never at a
   completed layer.
2. **Nothing is scheduled without a payer.** A capability with no waiting user goes to *Deferred*
   with its price of admission written down.

---

## The shape of the sequence

```
M0 ──► M1 ──► M2 │ M3 ──► M4 │ M5 ──► M6
└── shippable ───┘

M0–M2 is a complete product on its own:
"the reviewer now fixes what it finds."
No tracker, no plan engine, no gates.
```

That is what makes this order safe. The risky infrastructure — sandbox, harness, credential
injection, branch push — is paid for by a feature that stands alone. If M3 onwards never happened,
M0–M2 would still be something nobody else ships.

---

## M0 — Walking skeleton

**Goal:** prove the four unknowns that could sink the project, end to end, in one slice.

| Unknown | Proven by |
|---|---|
| does the sandbox work? | a container starts, holds a workspace, and is destroyed |
| does the harness stream parse? | NDJSON from `codex exec --json` becomes normalized events |
| does credential injection work? | the run authenticates without a credential in any layer or log |
| does the branch land? | a real commit reaches a real remote |

**Precondition — a spike, before this milestone's plan is written.** Does Codex's `workspace-write`
sandbox initialize inside a container? It is Landlock/seccomp-based and host-kernel-dependent, and the
common containerized practice is to disable it and let the container be the boundary. The exit
criterion below names that exact invocation, so the answer changes what M0 builds. Half a day. The
same house rule that put a spike before the auth work, where it overturned two of that plan's
predictions.

**Delivers.** `POST /api/runs` with a fixed prompt → `spire-run-worker` → `spire-runtime-docker` →
`spire-agent-codex` → workspace at an explicit base commit → **gated push**. No tracker, no gates, no
plan, no UI beyond a run row.

Three things land here that look like they belong later, and do not:

- **The push gate (FR-F28).** Retrofitting it means M0 ships the vulnerability and its exit criterion
  celebrates the ungated path.
- **The dedicated machine account (FR-F29).** Everything from M2 onward inherits this identity, and
  the credential work is M0's anyway.
- **`llm_charge`'s run shape** — neutral subject key, extended kind, `runId+attempt` call_ref, plus
  `capability` and `credential_ref`. M0 spends real money, and none of these can be backfilled. The
  argument that put them in M1 applies to M0 with more force.

**Modules.** `spire-harness`, `spire-harness-codex`, `spire-runtime`, `spire-runtime-docker`,
`spire-workspace`, `spire-run-worker`. `spire-arch` neutrality check extended **in the same commit as
the first seam**, with the per-name match modes ARCHITECTURE §10 specifies — an unanchored `pi` would
fail the build on the project's own name.

**Exit criteria — both halves, because the first alone celebrates the ungated path.**

1. A dispatched run against a real repository produces a branch on the real remote containing a commit
   the agent wrote and **authored by the machine account**, with the container destroyed afterwards
   and no credential in any image layer, log line or event payload.
2. A second run whose agent modifies a CI-configuration file is **refused at the push gate**,
   preserves its workspace, and raises an attention row naming the paths.

**Delivered (2026-09-01, PR #95).** Both criteria are proven by `M0WalkingSkeletonTest`
(`spire-run-worker`, `testServices` tier) against real containers — the publisher image built from
this repository, the reference agent entrypoint with a shell script standing in for the model, and a
self-built smart-HTTP git origin on the Docker network behind basic auth — and by runbook
**Mode Q** (`docs/SMOKE-TEST.md`) against a real forge with Codex. "Preserves its workspace" holds
in the sense §5 of RUN-TOPOLOGY gives it: every commit pushed before the gate tripped stays on the
branch; the refused commit itself is destroyed with the unit, as the design says it will be.

What the build taught that the design or the plan had wrong, each fixed in the code rather than
argued around:

- **The plan's automated test could not have run.** It assumed a `file://` origin, which no
  container can reach, and a runtime that fed the prompt on stdin, which none did — Codex declares
  stdin delivery and would have started on an empty prompt, which a script-harness test can never
  notice. The prompt now rides in `SPIRE_PROMPT` and the image entrypoint hands it over from a file
  outside the tree; the origin is a container.
- **The run unit named things nobody had built.** `spire-publisher:latest`, its `spire-clone`
  entrypoint and the codex agent image existed only as strings. `CloneMain` (JGit, read credential
  only, identity written into the workspace, remote removed), `spire-publisher/Dockerfile` and
  `deploy/agent/` are the delivery.
- **The runtime never pulled an image**, so the first CI run failed every container test on a
  runner that had never seen alpine. An operator's agent image is a registry reference by
  definition (FR-F13).
- **Two refusals were missing from the endpoint that spends the money**: a null harness
  credential (the key now comes from the LLM provider registry) and a re-dispatch of an existing
  subject that the worker's claim would have dropped as a redelivery while the operator held a 201.
  Plus the abbreviated `baseCommit` the resource accepted and the publisher's `ObjectId.fromString`
  rejected — after the agent had been paid for.
- **Criterion 2's attention row was in this document and in no plan task.** `RUN_PUSH_GATE_REFUSED`
  names the paths and clears on acknowledgement, like a failed review's row.
- **The neutrality scan's planned regex failed the build on the project's own name**: under `(?i)`,
  `\bPi[A-Z]` is `pipeline`. Case-sensitive for that one clause.
- **FR-F13 is split across M0 and M1** and the PRD now says so.

**FRs:** FR-F1..F4, F11, F13, F28, F29.

---

## M1 — The lifecycle survives reality

**Goal:** a run that meets a hostile world behaves correctly.

**Delivers.**

- Normalized run event stream on `cs.run-events`, tailable live, retained with a TTL.
- **Cancel**; steer where the harness declares the capability.
- **`finalize` before `destroy`** — commit, gate, push, extract usage, collect artefacts. **A failed
  salvage blocks teardown** and preserves the workspace; destroying on a failed push is exactly the
  loss this step exists to prevent.
- **Orphan watchdog** with a real orphan definition: leases carry `owner_id` and `heartbeat_at`, and
  an orphan is a sandbox whose lease is absent or stale past N intervals. Without that, two replicas
  on one daemon means the watchdog reaps a sibling's live hour-long run.
- **Run-worker channel semantics** — ack on receipt, `run_claim` as the sole idempotency mechanism,
  and cancel/steer over `cs.run-control` so a cancel does not queue behind the run it cancels.
- **Failure-cause discriminator** as a column, from a closed set.
- **Credential pool** with least-recently-exhausted rotation, `rejected` and
  `rate_limited` as distinct states in the schema, and `ALL_CREDENTIALS_EXHAUSTED`
  as a first-class refusal with an attention row. **Both states are entered by an operator, not by
  the pipeline** — nothing emits a credential refusal or a distinct rate limit yet, so rotation on
  exhaustion is manual. Guarded by `CredentialRefusalHasNoProducerTest`; tracked in
  `docs/UNVERIFIED.md` §A1–A2.
- Idempotency: intent journalled before dispatch, ambiguity failing closed.
- Enterprise image environment: CA bundle, proxy variables, private registry credentials.
- `spire agent-image verify`, reporting **verified** and **declared** clauses separately.

**Exit criteria.** Killing the control plane mid-run loses no completed work and creates no duplicate
charge. Killing the sandbox mid-run yields a classified failure, not a stall. Exhausting one
credential rotates to the next without re-charging the call.

**FRs:** FR-F5..F10, F12, F14.

---

## M2 — Deliver, and close the loop

**Goal:** the output becomes a pull request, the trigger becomes a real task, and the existing
reviewer reviews the result.

**Delivers.**

- **Fix a finding.** An existing review finding is dispatchable as a run — from the dashboard, or by
  a `/fix` comment on the pull request. The finding already carries repository, commit, file, line,
  message and suggestion, so it is a complete task specification with no tracker involved. This is
  what turns M0–M2 from infrastructure into a feature.
- **A new `PullRequestSink` port and three adapter implementations.** There is no `Forge` type in this
  codebase — that name came from prior art. The real ports are `ScmIngress` / `DiffSource` /
  `CommentSink`, and **none can open a pull request**; nothing here ever has. This is real work, not
  wiring, and the "M0–M2 is mostly plumbing" argument was too cheap before it was listed.
- **Git-push credentials**, also new: the registry token is brokered today for API calls only, and
  under ADR-038 the push credential belongs to the machine account. Whether one token serves both is
  decided here, per forge.
- The reviewer reviews the result; ADR-019 reconciliation handles round two unchanged. Run cost posted
  on the pull request.
- **Fix-chain accounting (FR-F32)** — a fix run records the finding id it addresses, and dispatch
  refuses past N fix runs for that finding.
- `factory_run` read model and the Runs screen: lifecycle strip, live event stream, budget panel,
  prompt.

**The fix target is not always a new pull request.** A fix for a finding on an open same-repository
pull request pushes to **that pull request's source branch**, because reconciliation is keyed per
review and a new pull request is a new review with no prior run — the original finding could never
resolve. Fork pull requests and default-branch findings get a new branch and a new pull request, and
the documents say plainly that reconciliation does not join there.

**Configuration rule enforced here:** the review model and prompt must differ from the build model
and prompt.

**`/fix` is gated, and does not inherit the observe-mode gap.** It follows `/review` and `/finding` in
checking the author allowlist ahead of the command switch, so a future command cannot arrive ungated.
It differs from them in one respect, deliberately: **`/fix` checks `policy.observeOnly()` and
refuses.** An earlier draft said it "inherits the known gap… and must not widen it from three paths to
four", which are the same thing said twice with opposite consequences. A reviewer commenting in
observe mode is a bug
(`techdebt/global/3-2-slash-finding-bypasses-observe-mode.md`); a factory *writing and pushing code*
in observe mode is a different order of failure, and it is not inherited here.

**Exit criterion.** A finding raised on an open pull request is dispatched as a fix run without a
tracker, pushes to that pull request's source branch, and the next review round reconciles the
original finding as resolved.

**FRs:** FR-F21, F27, F32.

**This is the shippable boundary.** Everything before it is infrastructure; everything after it is
scope.

---

## M3 — Work items, labels and gates

**Goal:** work starts from a ticket, and autonomy is chosen per ticket.

**Delivers.** `spire-worksource` plus GitHub Issues, GitLab Issues and Jira arms, reusing the
existing context adapters' clients. Tracker webhooks on the gateway's keyed registry edge.
`work_item` bookkeeping — **not** a mirror. Autonomy profiles, label mapping, the operator ceiling,
and a **per-work-source** actor allowlist. Durable gates answerable from the dashboard, the tracker or
a pull-request review, with expiry. Human takeover. The Work items and Approvals screens.

Two things the first draft assumed and had to be designed instead:

- **`labelEvents` carrying the actor**, not a bare set of label strings. A set has no author, and the
  allowlist rule needs one. Where neither a webhook nor a tracker audit trail can attribute a label,
  it is `UNATTRIBUTED` and **selects nothing** — the alternative is a rule that quietly applies only
  to labels a webhook happened to witness.
- **Policy re-resolved at every phase transition** (FR-F30), with the profile version pinned at
  admission, lowest-label-wins, and retirement when a tracker issue moves repository.

**Exit criteria.** A ticket labelled at each of three profiles produces three visibly different
journeys. A label naming a profile above the ceiling is clamped and says so. A label applied by an
actor outside the allowlist is ignored, **and so is one whose applier cannot be determined**. Lowering
the ceiling stops an in-flight item at its next phase.

**FRs:** FR-F16, F16a, F17, F22..F25, F30.

---

## M4 — Specification, plan and verify

**Goal:** the factory handles work that is bigger than one prompt.

**Delivers.** The `spec` phase — a vague ticket refined into outcome, context and acceptance
criteria, written back to the tracker. The `plan` phase — decomposition into ordered vertical slices,
one run per step, with a completion gate between steps and an empty-step rule so a step with nothing
to commit does not deadlock. The `verify` phase — repository-declared back-pressure, with
**`unverified`** as a distinct outcome from *passing*.

Plus **step continuity (FR-F31)**: each step's run ends by writing a structured summary — decisions
taken, alternatives rejected, deviations — stored on the work item and prepended to the next step's
prompt. Fresh context per step is the point, but the repository carries *what* was done and never
*why*, so without this a later step contradicts an earlier choice and the completion gate has nothing
to judge against.

**Exit criterion.** A ticket with no acceptance criteria becomes a specification, then a plan of
three or more steps, then three branches that each verify, then one pull request — **and step 3's
prompt demonstrably contains step 1's recorded decision.**

**FRs:** FR-F18..F20, F31.

---

## M5 — The seams become real

**Goal:** two implementations of each seam, because a seam with one arm is a claim.

**Delivers.** `spire-harness-pi` — MIT, provider-agnostic through the existing LLM registry, and the
first arm that can carry **steer** via its bidirectional session mode. `spire-runtime-k8s` — one Pod
per run, no Docker socket, real `NetworkPolicy`, TTL cleanup, landing into the Helm and kustomize
artefacts that already exist.

**Exit criterion.** The same work item runs to a pull request under both harnesses and both runtimes,
with no change to domain code and no new entry in the `spire-arch` allowlist that is not a
composition root.

**FRs:** FR-F15.

---

## M6 — Product modules and telemetry

**Goal:** the platform can be sold in parts and can account for itself.

**Delivers.** `Entitlements` checked at one choke point beside `SpendGate`, with
`entitlement_missing` as a first-class refusal reaching the UI status union. Per-capability metering
read back. Factory telemetry: cost per merged pull request, autonomy rate, issue-to-merge lead time,
where runs die, where the time goes.

**Exit criterion.** A month of operation answers, from stored data alone and without apportionment,
what each capability cost.

**FRs:** FR-F26.

---

## Deliberately not built

Each entry names why, and what would change the answer.

| Not built | Why | Price of admission |
|---|---|---|
| **Campaign controller / multi-repository campaigns** | Durable cross-repository policy execution is a second control plane. No waiting user. | A deployment running work across repositories that a single work item cannot express. |
| **Auto-merge (`land: auto_if_green`)** | The rung exists in the ladder and stays unwired. It needs a risk scorer, protected-path enforcement and a kill switch before it is safe. | An operator who has run at `land: approve` long enough to have dismissal-rate evidence. |
| **Learned routing / dispatch policy** | The corpus is collected; nothing consumes it as an input. A router on low volume learns noise. | Enough outcome-joined runs for a claim to survive its own confidence interval. |
| **Replica dispatch** (N runs, keep the best) | Multiplies spend for an unproven gain, and needs a comparator nobody has written. | A measured case where one frontier run loses to several cheap ones. |
| **Sidecar harness injection** | The better end state; the image contract makes deferring it safe. Node-based harnesses need a bundled runtime. | Repeated friction from customers who cannot rebuild an image per harness update. |
| **A second issue tracker (our own backlog)** | Every customer already has one, and owning a second means owning sync, drift and permissions. | A deployment with no tracker at all that still wants the factory. |
| **An issues CRUD UI** | The tracker is the source of truth; a browser CRUD surface over it buys a sync problem. | — |
| **MCP server management** | Project tooling the agent starts inside the sandbox, with credentials the project supplies. | — |
| **Behaviour validation contracts / monitoring agents** | Recorded from the prior art as a real idea. Nothing to monitor until the factory runs continuously. | The factory running unattended for long enough that drift is a real risk. |
| **Mode-collapse mitigation (generation wiping)** | Only matters for open-ended iterative work, which is not a phase here. | An autonomy workload that iterates towards a goal rather than completing a work item. |

## Pre-M0 questions — closed 2026-09-01

An adversarial review promoted six items from "open question" to "must answer before M0". Five are now
decided; the sixth needs a fact only the operator holds.

| # | Question | Answer | Where |
|---|---|---|---|
| 1 | Does Codex's sandbox initialize inside a container? | **Measured: NO.** Codex ships **bubblewrap**, and Docker's default seccomp refuses the user namespace it needs. (An earlier probe measured Landlock and said yes — wrong primitive.) Decision: keep default seccomp, run `--sandbox danger-full-access`, **the container is the boundary**. And Codex is confirmed to *work* in a container: it answered a prompt and completed a real agentic edit-and-commit on subscription auth. | [RUN-TOPOLOGY §1](./RUN-TOPOLOGY.md) |
| 1b | Where does the workspace live, across replicas and nodes? | **In the run pod, nowhere else** (ADR-039). The pod clones itself, checkpoints continuously to the branch, and is destroyed; the forge is the durable state. The bind-mounted-workspace design made the worker stateful and broke run recovery. | [RUN-TOPOLOGY §2–3](./RUN-TOPOLOGY.md) |
| 2 | Does one token serve both forge API and git push? | **Yes on all three forges** — GitHub App installation token, GitHub PAT, GitLab PAT with `write_repository`, Bitbucket API token. One credential per (machine account, provider); `separatePushCredential` is a declared capability, false everywhere today, so a forge that splits them later is an adapter change. Never the review bot's credential; injected per run; never URL-embedded. | [EXECUTION-LAYER §3.4](./EXECUTION-LAYER.md) |
| 3 | The protected-path matcher and refusal surface | **The JDK's `PathMatcher` with `glob:` syntax** — no new dialect, no dependency. (`PathGlobs` was named in a first draft and is the wrong tool: it maps a path *to* a group glob, it does not match one *against* a glob.) Match the changed-path set against base, **both sides of a rename**, **deletions included**; the CI floor matches **case-insensitively**. Refusal is `push_gate_refused`, naming every blocked path. | [AUTONOMY §5](./AUTONOMY.md) |
| 4 | The run charge row's shape | `review_id` → **`subject_id` + `subject_kind`** (`REVIEW`\|`RUN`); `kind` CHECK extended with `SPEC`, `PLAN`, `BUILD`, `FIX`; `CallRefs` gains `run:{runId}:{seq}` (the attempt already lives in the run id, so a second `{attempt}` segment would restate it). Ten existing reads updated in the same migration. A run id in a column named `review_id` was rejected outright. | [ARCHITECTURE §7](./ARCHITECTURE.md) |
| 5 | The run worker's channel semantics | **Its own topics** — `cs.run-commands` / `cs.run-control` / `cs.run-results` — because `ActionCommand` declares `reviewId()` as mandatory and a run has a `runId`; a run id behind a method named `reviewId()` is a name that lies. The worker **writes `run_claim` then acks** (that order — the reverse loses the command on a crash); concurrency is a bounded executor, not consumer parallelism. | [ARCHITECTURE §5.1](./ARCHITECTURE.md) |
| 6 | Subscription-decision provenance | **Closed.** The operator raised it with **OpenAI support** and was told the use is permitted; recorded 2026-09-01. The published terms leave it open, the vendor's support answer settles it for this deployment, and the ticket in the operator's support history is the artifact if it is challenged. | [ADR-031](../DECISIONS.md) |

## Design questions — closed 2026-09-01

All five carried from the first draft are now decided. Each is written here so the reason survives; a
reopened question needs new evidence, not a fresh opinion.

**1. The `spec` phase writes to the tracker, and not to the repository.** A comment on the work item,
plus the structured form on `work_item` for the pipeline's own use. Writing a spec file into the
repository would create a second source of truth against ADR-029, and — worse — a diff the reviewer
must then review, so every specification would cost a review round before any code existed. The
tracker is where the work lives and where the humans already are.

**2. `verify` fails a step, never a work item.** A failed step fails its step; the plan coordinator
spends its retry budget, and what happens after that is a gate decision, not an automatic verdict.
Letting one failed verification kill the work item would discard every completed step, which is the
mistake the plan-run child-retry model already avoids. `unverified` propagates upward as a fact the
gate sees, not as a failure that pre-empts it.

**3. Entitlements are a registry entry the operator sets** — see
[PACKAGING §6](./PACKAGING.md). Not a signed licence file, and the reasoning is worth keeping: a
signed file implies technical enforcement against the operator, needs key management and a revocation
story, and would be theatre on self-hosted software whose database the operator owns. The FSL text is
the enforcement; entitlements make the shape legible and the billing honest.

**4. `spec` and `plan` are single model calls through the existing `LlmProvider`. No sandbox, no
harness.** Both are context-light reasoning that edits no files, and the context they need — the
repository's rules, the symbol index, linked issues — already arrives through `ContextProvider`.
Spending a container and a tool loop on them buys nothing.

Two consequences worth stating. `spec` and `plan` are **metered** through the existing ledger even on
an unmetered Codex deployment, so a deployment always has some real cost signal. And only `build` and
`verify` need a harness at all, which means an operator can run the planning half of the factory
before ever configuring a sandbox.

**5. Licence provenance is a report at `verify`, not a gate at `deliver`** — for now, and the "for
now" is the honest part. A blocking gate needs a scanner with a false-positive rate nobody has
measured, and a gate that cries wolf gets disabled, which is worse than a report nobody promised. So:
`verify` runs a provenance report, the run records its harness and model so any finding is
attributable, and the report rides on the pull request where a human sees it. It becomes a gate when
there is evidence about its accuracy — the same evidence-before-enforcement ladder used everywhere
else here. OpenAI's own terms are why the step exists at all: *"Output generated by code generation
features of our Services, including OpenAI Codex, may be subject to third party licenses."*

## Still open

**Nothing.** All eleven questions carried into this design are closed — five by decision, five by
refusing the more elaborate option or choosing the smaller mechanism, and one (the Codex sandbox) by
measurement. The last, the subscription provenance, was closed by the operator asking OpenAI support.

What remains before M0 is not a question but work: the M0 implementation plan itself.

**The standing obligation is unchanged, and it is not a question.** Vendor terms for automated use
changed twice during 2026, and one of this design's positions rests on a support answer rather than a
published term. Every finding in [EXECUTION-LAYER §2](./EXECUTION-LAYER.md) carries its retrieval
date, and each is re-read before the arm it governs ships.
