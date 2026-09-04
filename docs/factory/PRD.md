# Software Factory — Product Requirements

> Extends [`../PRD.md`](../PRD.md), which owns the reviewer (FR-1..13). This document owns the
> factory (FR-F1..F32). Where the two overlap, the reviewer's requirements are unchanged and the
> factory reuses them.

---

## 1. Problem & motivation

A reviewer finds defects and stops. Every finding it raises becomes work for a human, so a better
reviewer produces *more* human work, not less. The bottleneck in a modern engineering team has moved:
writing code is now minutes, and reviewing it is still hours. Adding more coding agents does not help
— it deepens the queue in front of the bottleneck.

Two things follow.

**First, the loop should close.** The reviewer already knows the file, the line, the defect and the
suggested fix, and already tracks whether the fix landed. Everything needed to *make* the change is
present at the moment the finding is raised. Handing that to a human to retype is waste.

**Second, the work item is the right unit, not the diff.** Real work arrives as a ticket, not as a
pull request. A factory that starts from a tracker issue can run the phases a team already runs —
refine, plan, build, verify, review, deliver — with an agent in each, and a human only where judgment
is genuinely required.

The failure mode to avoid is equally clear from the prior art: an unsupervised agent fleet that
produces volume nobody can review, in a codebase nobody understands any more. The design therefore
treats **back-pressure** (tests, gates, review, budgets) and **human gates** as load-bearing, not as
optional polish.

## 2. Users

| User | Needs |
|---|---|
| **Operator** (owns the deployment) | Credentials, budgets, autonomy ceilings, image policy, and a truthful account of what ran and what it cost. |
| **Maintainer** (owns the repo) | Control over what the factory may touch, evidence for every change, and an approval seat at the phases that matter to them. |
| **Contributor / reporter** | A ticket that gets refined and worked without them learning a new tool. They interact through the tracker and the pull request, never through Code Spire. |
| **Platform / security team** | An image they approved, an egress policy they set, a credential that never leaves their boundary, and an audit trail. |

The factory serves **one deployment, one trusted team**, consistent with ADR-001 and ADR-021. It is
not multi-tenant SaaS.

## 3. Goals & non-goals

### Goals

1. Turn a tracker work item into a reviewed pull request without a human typing code.
2. Make the degree of automation a property **of the work item**, chosen per ticket, bounded per repo.
3. Keep every paid action metered, capped, and attributable — before it is spent, not after.
4. Never lose completed work, even when the sandbox, the node or the control plane dies.
5. Let the existing reviewer review the factory's own output, and record the result as a corpus.
6. Run on a laptop with Docker today and on Kubernetes later, behind one contract.

### Non-goals

1. **Not a merge decision-maker by default.** The guaranteed output is a pushed branch. Merging is a
   policy the operator opts into per profile, never a default.
2. **Not a second issue tracker.** The customer's tracker is the source of truth. No issues table, no
   issues UI, no sync (ADR-029).
3. **Not a multi-repo campaign engine.** Cross-repository policy execution is deferred with a named
   price of admission (see [ROADMAP.md](./ROADMAP.md)).
4. **Not a learned router.** Dispatch decisions are deterministic code. The corpus that would train a
   router is collected; nothing consumes it as a policy input yet.
5. **Not a harness.** Code Spire drives existing agent harnesses. It does not implement a tool loop,
   a context compaction strategy, or a permission prompt.
6. **Not a model reseller.** The operator's credential, the operator's bill (ADR-031).

## 4. Functional requirements

Tags: **[M0]**–**[M6]** map to the build order in [ROADMAP.md](./ROADMAP.md).

### 4.1 Run kernel

- **FR-F1 — Dispatch a run [M0].** An authorized caller can dispatch a run against a registered
  repository with a prompt, a base ref, a harness, a model or credential selection, and caps. The run
  is admitted or refused synchronously; a refusal names its reason.
- **FR-F2 — Isolated workspace [M0].** Each run receives a fresh clone or worktree at an explicit
  base commit, writable only within the workspace. The workspace is always fresh; the **branch** is
  not always new. A run creates its own branch by default, and checks out an existing branch when the
  dispatch targets one (see FR-F27). Freshness of the checkout and novelty of the branch are separate
  properties and must not be conflated.
- **FR-F3 — Sandboxed execution [M0].** The harness process runs inside an isolation boundary the
  operator selected for the deployment, with declared CPU, memory, disk, wall-clock and network
  policy. Network egress defaults to deny.
- **FR-F4 — Guaranteed output [M0].** A successful run's guaranteed artefact is a **pushed workspace
  branch — unless the branch fails the push gate (FR-F28)**. Everything after the push is policy. The
  caveat is load-bearing, not pedantry: an unqualified guarantee is what would make the push
  unconditional, and an unconditional push is the vulnerability (ADR-037).
- **FR-F5 — Live run events [M1].** A run emits a normalized event stream — reasoning, tool calls,
  tool results, output, state transitions — tailable live and retained for a bounded window.
- **FR-F6 — Cancel and steer [M1].** A run can be cancelled at any time. Where the harness declares
  the capability, a run can also be steered mid-flight with an additional instruction.
- **FR-F7 — Salvage before teardown [M1].** Before a sandbox is destroyed, the run is finalized:
  commit, gate, push, extract usage, collect artefacts. Teardown never precedes salvage. **A failed
  salvage blocks teardown**: an expired token, a protected branch, or the forge rejecting a committed
  secret preserves the workspace, classifies the failure and raises an attention row. The workspace is
  reclaimed by an operator action or by expiry — never by the failure path itself, which would destroy
  exactly the work this requirement exists to keep.
- **FR-F8 — Orphan reconciliation [M1].** A sandbox the control plane lost — restart, eviction, node
  loss — is discovered and reaped by a watchdog, not leaked.
- **FR-F9 — Failure classification [M1].** Every failed run carries a discriminated cause from a
  closed set, recorded as data. "Read the logs" is not a failure cause.
- **FR-F10 — Idempotent dispatch [M1].** A lost dispatch response never causes a duplicate paid run.
  Intent is journalled before the request; an ambiguous outcome fails closed into an explicit
  uncertain state requiring resolution.

### 4.2 Execution layer

- **FR-F11 — Harness registry [M0].** The harness driving a run is a runtime registry selection, not
  a build-time dependency. Adding a harness is an adapter plus a registry entry.
- **FR-F12 — Credential pool with rotation [M1].** An operator may register several credentials the
  factory calls the model with, kept separate from the reviewer's own key and never falling back to
  it. On exhaustion the pool rotates to the member that has rested longest. Exhaustion of the whole
  pool is a first-class refusal naming when capacity returns, and how much of the pool will not
  return without a new key. **As delivered the pool is not scoped per harness** — one pool serves
  every arm, so a deployment running two arms that need different vendors must keep that in mind;
  the original wording promised per-harness registration that the shipped table does not have.
  **And rotation on exhaustion is operator-driven**: nothing in the pipeline reports a credential
  refusal or a rate limit yet, so a dead key is retired by hand. See
  `techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md`.
- **FR-F13 — Bring-your-own image [M0 / M1].** The agent image is a published contract any image
  may satisfy, verifiable by a conformance command. Shipped images are reference implementations,
  never mandatory. Image references are digest-pinnable for air-gapped mirrors. **Split across two
  milestones, on purpose:** M0 delivers the half the walking skeleton needs — `agentImage` is a
  per-run parameter carried on `ExecuteRun` and honoured by the runtime, a digest reference works,
  and the reference image's entrypoint contract (`deploy/agent/spire-agent-entrypoint.sh`: prompt on
  stdin, commits to bundles on `/handoff`, `DONE` last) is what any image must provide. **The M1
  half is delivered:** the contract is written down in `docs/factory/AGENT-IMAGE-CONTRACT.md` and
  `spire agent-image verify <image>` checks it. The report has two halves that never mix --
  **verified** clauses the command proved against the image, and **declared** clauses the image
  claims through a label and the command cannot prove. The split is structural rather than a flag:
  a declaration has no pass/fail component, so reporting one as verified is inexpressible. A report
  that blended them would read as proof, and an image declaring a toolchain it does not carry would
  pass with the first thing to notice being a run already paid for. `ContractAndCheckerAgreeTest`
  fails the build when the document and the checker disagree in either direction.
- **FR-F14 — Enterprise image environment [M1, delivered].** Corporate CA bundles, proxy variables
  and private registry credentials are honoured, all injected at run time and never baked into an
  image. The bundle and the proxy live on `RunUnitSpec` rather than on any one container, so
  "every container of the unit" is structural and no arm can apply them to two parts out of three;
  the registry credential lives on the RUNTIME instead, because everything on a unit spec reaches a
  container, where `docker inspect` prints it and the agent can read its own environment. A missing
  bundle path or a half-supplied registry credential is a startup refusal. Operator guidance in
  `deploy/agent/CORPORATE-ENVIRONMENT.md`; the no-baking half is build-enforced by
  `NoCorporateEnvironmentIsBakedIntoAnImageTest`.
- **FR-F15 — Second harness arm [M5].** At least two harness implementations exist, proving the seam
  rather than asserting it.

### 4.3 Work items and phases

- **FR-F16 — Work source [M3].** A tracker is readable as a work queue: list candidates, read one,
  comment, transition, and read labels. No issue is mirrored into Code Spire's database; only its own
  run bookkeeping is stored, keyed by `(provider, repository, issue id)`.
- **FR-F16a — Attributable labels [M3].** A work source exposes label **events carrying the actor who
  applied them**, either from a webhook or by reading the tracker's own audit trail. A bare set of
  label strings is not sufficient, because FR-F24 must know who applied a label. A label whose applier
  cannot be attributed **selects no profile**. Without this, enforcement silently degrades to
  "labels the webhook happened to witness", and every label found by polling — after downtime, on a
  backfill, on the first scan of an existing backlog — bypasses the check entirely.
- **FR-F17 — Eight phases [M3/M4].** A work item moves through `intake → spec → plan → build →
  verify → review → deliver → land`. Each phase is separately gateable.
- **FR-F18 — Specification phase [M4].** A vague ticket is refined into an outcome, context and
  acceptance criteria, **written back to the tracker as a comment** — never into the repository,
  which would create a second source of truth and a diff the reviewer must review before any code
  exists. `spec` and `plan` are **single model calls through the existing `LlmProvider`**: they edit
  no files, their context already arrives through `ContextProvider`, and a sandbox buys them nothing.
  A consequence worth having: both are metered through the existing ledger even where `build` is not.
- **FR-F19 — Plan phase [M4].** A specification is decomposed into ordered steps sized as vertical
  slices — each ending at something runnable — and executed one at a time with a completion gate
  between steps.
- **FR-F20 — Verify phase [M4].** A step is verified by repository-declared back-pressure: build,
  tests, linters and any gate command the repository names. A step that cannot be verified is
  reported as unverified, never as passing. **`verify` fails a step, never a work item** — the plan
  coordinator spends its retry budget and a gate decides what follows, because one failed
  verification killing the item would discard every completed step. `unverified` propagates upward as
  a fact the gate sees, not as a verdict that pre-empts it. The phase also emits a **licence
  provenance report** (NFR-F10) rather than a gate: a blocking check needs a false-positive rate
  nobody has measured, and a gate that cries wolf gets switched off.
- **FR-F21 — Review phase [M2].** A delivered pull request is reviewed by the existing reviewer, with
  reconciliation across rounds. The review model and prompt must differ from the build model and
  prompt.
- **FR-F22 — Human takeover [M3].** A person pushing to the branch or commenting on the pull request
  suspends automation for that work item until an operator resumes it.
- **FR-F27 — Fix a finding [M2].** An existing review finding can be dispatched as a run without a
  tracker. The finding already carries repository, commit, file, line, message and suggestion, so it
  is a complete task specification. This is what makes M0–M2 a product rather than infrastructure.

  **The target depends on where the finding lives, and this is not cosmetic.** Reconciliation is
  keyed per review, and a review id is derived from `workspace/slug#prId`. A fix delivered as a *new*
  pull request is a *new* review with no prior run, so the original finding could never reconcile and
  would stay open forever.

  | Finding is on | Target | Reconciliation |
  |---|---|---|
  | an open pull request in the same repository | **that pull request's source branch** | round two joins; the finding resolves |
  | an open pull request from a fork | new branch + new pull request | does not join — stated, not hidden |
  | a merged commit or the default branch | new branch + new pull request | no prior run to join |

- **FR-F28 — Push gate [M0].** Between commit and push, the run plane diffs the branch against its
  base and **refuses the push** when the change touches a protected path. A refused push is a
  classified failure that preserves the workspace and raises an attention row naming the paths.
  **CI-configuration paths are a floor no profile may lower** (`.github/workflows/**`,
  `.gitlab-ci.yml`, `.gitlab/**`, `bitbucket-pipelines.yml`, `Jenkinsfile`, `.circleci/**`).
  Without this, a pushed branch executes agent-authored workflow files on the repository's own CI —
  unsandboxed, with repository secrets — and every sandbox control in this document is bypassed by
  the kernel's own guaranteed output (ADR-037).
- **FR-F29 — Factory identity [M0].** The factory pushes and opens pull requests as a **dedicated
  machine account**, registered separately from the review bot. `factory_run` records the identity it
  pushed as, and a factory-authored pull request is marked by an **attribute**, never inferred from an
  account name. Without a separate identity, allowlisting the factory would grant the review bot
  allowed-author rights on every command surface — the widening ADR-036 forbids — and human takeover
  (FR-F22) would have no reliable way to tell a person from an agent (ADR-038).
- **FR-F30 — Policy is re-resolved per phase [M3].** Profile, ceiling and allowlist are re-read at
  **every phase transition**, not once at intake. The profile **version** is pinned at admission, so
  an edited definition does not retroactively change an in-flight item; moving it is an explicit
  re-admission. A work item whose tracker issue is transferred to another repository — which changes
  the identity it is derived from — is retired, not silently continued.
- **FR-F31 — Step continuity [M4].** Each plan step's run ends by writing a structured summary —
  decisions taken, alternatives rejected, deviations from the plan — stored on the work item and
  prepended to the next step's prompt. Fresh context per step is the point (it keeps every run in the
  model's good zone), but the repository carries *what* was done and never *why*, so without this a
  later step re-derives or contradicts an earlier choice and the completion gate has no record to
  judge against.
- **FR-F32 — Bounded fix chains [M2], on two axes.** A fix run records **both** the finding it
  addresses and the review it belongs to; dispatch refuses past N fix runs for one finding **and**
  past M for one review. Two axes because one does not bound the loop this requirement is about:
  each hop of "a finding spawns a fix, whose review raises a finding, which spawns a fix" raises a
  **new** finding with a new identity, so a per-finding counter never reaches N. The per-review axis
  is what bounds the chain — and under ADR-040 a fix stays on the reviewed pull request, so one
  review IS the chain. The per-finding axis still earns its place: it stops repeated attempts at one
  stubborn finding. The finding reference must be stable across rounds, which `review_finding.id` is
  not (P4 rewrites those rows delete-then-insert per round); use `(review_id, thread_ref)`, which is
  what ADR-019 reconciliation already keys on.

### 4.4 Autonomy, gates and policy

- **FR-F23 — Autonomy profiles [M3].** An operator defines named profiles. A profile assigns each
  phase a mode of `auto`, `approve` or `off`, plus caps for runs, wall clock, spend and protected
  paths.
- **FR-F24 — Label-selected autonomy [M3].** A work item selects a profile by tracker label. A label
  may only select a profile at or below the repository's operator-set ceiling; a higher selection is
  clamped visibly, never silently, and never upward. Where several profile labels are present, the
  **lowest wins**. A label applied by an actor outside the **work source's own** allowlist does not
  count, and neither does one whose applier cannot be attributed (FR-F16a).
  The allowlist is registered **per work source**, not reused from the SCM author allowlist: a Jira
  labeller has a Jira account id and no SCM provider row, so comparing them crosses identity spaces —
  the class of bug that once cross-wired two SCMs sharing a workspace name.
- **FR-F25 — Durable gates [M3].** An `approve` phase persists an approval request that appears in the
  attention panel and can be answered from the dashboard, a tracker comment, or a pull-request review.
  Gates expire into a terminal state rather than holding a reservation indefinitely.
- **FR-F26 — Capability entitlement [M6].** Every dispatch is checked against the deployment's
  entitlements at one choke point. A blocked capability produces a first-class refusal with its own
  status and attention row, never a crash or a silent skip. Every charge records the capability that
  caused it.

## 5. Non-functional requirements

- **NFR-F1 — Isolation is enforced, not requested.** A policy statement such as "read-only" is
  meaningless until the execution environment enforces it. Every declared restriction maps to a
  mechanism.
- **NFR-F2 — Least authority, narrowing downward.** Authority narrows as configuration moves from
  operator to repository to run. No lower layer widens a higher one.
- **NFR-F3 — No credential in an image or a log.** Credentials are injected at start and redacted
  from every event, artefact and transcript.
- **NFR-F4 — Bounded cost and time.** Every run carries a spend cap, a wall-clock cap and an attempt
  cap resolved before dispatch. Unbounded execution is not reachable by configuration.
- **NFR-F5 — Restart safety.** Any component may restart at any point without duplicating paid work,
  losing completed work, or resurrecting a terminated item.
- **NFR-F6 — Two event tiers.** High-volume run events never enter the aggregate's durable log. The
  aggregate records milestones only (ADR-034).
- **NFR-F7 — Provider neutrality, build-enforced.** Core modules name no harness, runtime or work
  source outside an explicit reasoned allowlist, checked by `spire-arch` on the same terms as SCM
  providers today (ADR-020).
- **NFR-F8 — Observability by construction.** Every phase transition, refusal and gate decision is
  recorded with its deciding factors. A skip that reaches only a dashboard is a defect; the project
  has paid for this lesson twice.
- **NFR-F9 — Honest degradation.** A degraded outcome is never rendered as a successful one. Every
  new terminal state carries its own status vocabulary end to end, including the UI type system.
- **NFR-F10 — Licence hygiene.** Agent harnesses are installed under their own licences, never
  redistributed. Generated code may carry third-party licence obligations and the pipeline provides a
  place to check for them.

## 6. Scope & phasing

| Milestone | Delivers | FRs |
|---|---|---|
| **M0** | Walking skeleton: dispatch → sandbox → harness → **gated** push | FR-F1..F4, F11, F13, F28, F29 |
| **M1** | Real lifecycle: events, cancel, salvage, watchdog, taxonomy, credential pool | FR-F5..F10, F12, F14 |
| **M2** | Fix a finding; deliver a pull request; the existing reviewer reviews it | FR-F21, F27, F32 |
| **M3** | Work source, labels, autonomy profiles, gates, human takeover | FR-F16, F16a, F17, F22..F25, F30 |
| **M4** | Specification, plan and verify phases | FR-F18..F20, F31 |
| **M5** | Second harness arm; Kubernetes runtime arm | FR-F15 |
| **M6** | Entitlements, per-capability metering, factory telemetry | FR-F26 |

**FR-F28 and FR-F29 are in M0 deliberately.** Both were added after a review found the walking
skeleton's exit criterion celebrating an ungated push by an unspecified identity. Retrofitting either
means M0 ships the vulnerability and M2 inherits an identity decision it cannot change.

**M0–M2 is a shippable product on its own** — *the reviewer now fixes what it finds* — needing no
tracker, no plan engine and no gates. That is what makes the build order safe: the risky
infrastructure is paid for by a feature that stands alone.

## 7. Assumptions & constraints

1. The operator supplies model credentials and is the party bound by the model vendor's terms
   (ADR-031). Code Spire never resells model access.
2. Subscription-authenticated harness runs have **no per-token price**. The money cap is inert for
   them by design; the call-count cap and the quota-headroom check are the live controls (ADR-025
   predicted exactly this).
3. The Docker runtime arm requires access to a Docker socket, which is root-equivalent on the host.
   This is stated in `SECURITY.md` rather than mitigated away.
4. A repository's own toolchain must exist inside the sandbox or verification is impossible. That is
   the operator's image to build; Code Spire supplies the contract, not every stack.
5. Vendor terms for automated use changed twice during 2026. Every finding is recorded with its
   retrieval date and re-checked before a harness ships.
6. **Measured, and the opposite of an earlier assumption:** Codex's own sandbox does **not** work in
   a container. It is bubblewrap-based, and Docker's default seccomp refuses the user namespace it
   needs; an earlier probe measured Landlock and drew the wrong conclusion from the wrong primitive.
   **The container is the sole boundary**, Codex runs `--sandbox danger-full-access`, and the
   container is therefore made genuinely restrictive. Separately confirmed: Codex *does* run, reason,
   edit files and commit inside a container. See [RUN-TOPOLOGY §1](./RUN-TOPOLOGY.md).
7. **Decided:** the egress allowlist is per repository and **seeded by observation** — early runs
   record egress without blocking, the operator promotes the observed set, later runs enforce it. The
   model endpoint, git host and forge API are always allowed. A newly added dependency produces a
   `blocked_egress` failure cause naming the host, not a mysterious build error.

## 8. Success criteria

**M0** — a dispatched run against a real repository produces a branch on the real remote containing a
commit the agent wrote **and authored by the dedicated machine account**, with the container destroyed
afterwards and no credential in any layer, log or event. A second run whose agent modifies a
CI-configuration file is **refused at the push gate**, preserves its workspace, and raises an
attention row. Both halves are required: the first alone celebrates the ungated path.

**M1** — killing the control plane mid-run loses no completed work and creates no duplicate charge;
killing the sandbox mid-run yields a classified failure, not a stall; exhausting one credential
rotates to the next without re-charging the call.

**M2** — a review FINDING (no work item; those are M3) is dispatched as a fix run, the fix pushes to
the open pull request's own source branch under ADR-040, the existing reviewer reviews the result,
and round two reconciles the original finding as resolved.

**M3** — a ticket labelled at each of three profiles produces three visibly different journeys; a
label above the ceiling is clamped and says so; a label applied by an unlisted actor is ignored.

**M6** — a month of operation answers, from stored data alone: cost per merged pull request, autonomy
rate, issue-to-merge lead time, where runs die, and what each capability cost.

## 9. Traceability

| Requirement group | Decisions | Design |
|---|---|---|
| Run kernel | ADR-029, ADR-034 | [ARCHITECTURE.md](./ARCHITECTURE.md) |
| Execution layer | ADR-030, ADR-031, ADR-032 | [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) |
| Work items, phases, gates | ADR-033, ADR-036 | [AUTONOMY.md](./AUTONOMY.md) |
| Packaging and entitlement | ADR-035 | [PACKAGING.md](./PACKAGING.md) |
| Push gate and CI floor | **ADR-037** | [AUTONOMY.md](./AUTONOMY.md) §5 |
| Factory identity | **ADR-038** | [ARCHITECTURE.md](./ARCHITECTURE.md) §4 |
