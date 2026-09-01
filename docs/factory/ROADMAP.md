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
- **Credential pool** with least-recently-exhausted rotation, `rejected` versus `rate_limited`
  distinguished, and `ALL_CREDENTIALS_EXHAUSTED` as a first-class refusal with an attention row.
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
  under ADR-037 the push credential belongs to the machine account. Whether one token serves both is
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

## Must be answered before M0 starts

These are not "questions to settle at M4". Each one changes what M0 builds, and an adversarial review
found every one of them assumed rather than decided.

| # | Question | Why it cannot wait |
|---|---|---|
| 1 | **Does Codex's sandbox initialize inside a container?** | M0's exit criterion names that exact invocation. A half-day spike. |
| 2 | **Whose credential pushes** — confirmed as a dedicated machine account (ADR-037), so: how is it registered, and does one token serve both API and git push? | M0 does the credential work; M2 cannot change the identity afterwards. |
| 3 | **Is the push gated?** Settled as yes (ADR-036, FR-F28). Remaining: the exact protected-path matcher and how a refusal surfaces. | Retrofitting means M0 ships the vulnerability. |
| 4 | **The run charge row's shape** — subject key, kind, `call_ref` scheme. | M0 spends real money into a ledger whose spine is review-shaped and rejects unknown kinds at INSERT. |
| 5 | **The run worker's channel semantics** — ack model and the cancel path. | It decides whether `cs.commands` is even the right topic, before the first consumer is written. |
| 6 | **Record the subscription-decision provenance**, or stop claiming it is recorded. | One sentence, and it is what protects the position if the policy moves. |

## Open questions

1. **Where does the spec phase write?** Back to the tracker as a comment, or into the repository as a
   file? A comment is visible where the work lives; a file is versioned and reviewable. Likely both,
   decided at M4 by which one the reviewer can cite.
2. **Should `verify` be able to fail a work item, or only a step?** A step that cannot verify is
   clear. A work item whose final state is unverified is a judgment call.
3. **How are entitlements delivered** — signed licence file, registry entry, or operator toggle? All
   three fit behind the same type; the choice does not change a boundary.
4. **Does the plan phase need its own harness invocation, or is it a single model call?** Planning is
   context-light and mostly reasoning, which argues for the cheaper path.
5. **Licence provenance for generated code** (NFR-F10) — a scanner in `verify`, a gate at `deliver`,
   or a report only? OpenAI's own terms say the output may carry third-party licences; the pipeline
   needs a place for that, and which place is undecided.
