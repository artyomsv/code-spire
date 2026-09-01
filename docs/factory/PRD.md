# Software Factory — Product Requirements

> Extends [`../PRD.md`](../PRD.md), which owns the reviewer (FR-1..13). This document owns the
> factory (FR-F1..F26). Where the two overlap, the reviewer's requirements are unchanged and the
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
   issues UI, no sync (ADR-028).
3. **Not a multi-repo campaign engine.** Cross-repository policy execution is deferred with a named
   price of admission (see [ROADMAP.md](./ROADMAP.md)).
4. **Not a learned router.** Dispatch decisions are deterministic code. The corpus that would train a
   router is collected; nothing consumes it as a policy input yet.
5. **Not a harness.** Code Spire drives existing agent harnesses. It does not implement a tool loop,
   a context compaction strategy, or a permission prompt.
6. **Not a model reseller.** The operator's credential, the operator's bill (ADR-030).

## 4. Functional requirements

Tags: **[M0]**–**[M6]** map to the build order in [ROADMAP.md](./ROADMAP.md).

### 4.1 Run kernel

- **FR-F1 — Dispatch a run [M0].** An authorized caller can dispatch a run against a registered
  repository with a prompt, a base ref, a harness, a model or credential selection, and caps. The run
  is admitted or refused synchronously; a refusal names its reason.
- **FR-F2 — Isolated workspace [M0].** Each run receives a fresh clone or worktree at an explicit
  base commit, on its own branch, writable only within the workspace.
- **FR-F3 — Sandboxed execution [M0].** The harness process runs inside an isolation boundary the
  operator selected for the deployment, with declared CPU, memory, disk, wall-clock and network
  policy. Network egress defaults to deny.
- **FR-F4 — Guaranteed output [M0].** A successful run's guaranteed artefact is a **pushed workspace
  branch**. Everything after that is policy.
- **FR-F5 — Live run events [M1].** A run emits a normalized event stream — reasoning, tool calls,
  tool results, output, state transitions — tailable live and retained for a bounded window.
- **FR-F6 — Cancel and steer [M1].** A run can be cancelled at any time. Where the harness declares
  the capability, a run can also be steered mid-flight with an additional instruction.
- **FR-F7 — Salvage before teardown [M1].** Before a sandbox is destroyed, the run is finalized:
  commit, push, extract usage, collect artefacts. Teardown never precedes salvage.
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
- **FR-F12 — Credential pool with rotation [M1].** An operator may register several credentials for
  one harness. On quota exhaustion or rate limiting the pool rotates to the least-recently-exhausted
  member. Exhaustion of the whole pool is a first-class refusal naming when capacity returns.
- **FR-F13 — Bring-your-own image [M0].** The agent image is a published contract any image may
  satisfy, verifiable by a conformance command. Shipped images are reference implementations, never
  mandatory. Image references are digest-pinnable for air-gapped mirrors.
- **FR-F14 — Enterprise image environment [M1].** The contract requires corporate CA bundles, proxy
  variables and private registry credentials to be honoured, all injected at run time, never baked
  into an image.
- **FR-F15 — Second harness arm [M5].** At least two harness implementations exist, proving the seam
  rather than asserting it.

### 4.3 Work items and phases

- **FR-F16 — Work source [M3].** A tracker is readable as a work queue: list candidates, read one,
  comment, transition, and read labels. No issue is mirrored into Code Spire's database; only its own
  run bookkeeping is stored, keyed by `(provider, repository, issue id)`.
- **FR-F17 — Eight phases [M3/M4].** A work item moves through `intake → spec → plan → build →
  verify → review → deliver → land`. Each phase is separately gateable.
- **FR-F18 — Specification phase [M4].** A vague ticket is refined into an outcome, context and
  acceptance criteria, written back to the tracker.
- **FR-F19 — Plan phase [M4].** A specification is decomposed into ordered steps sized as vertical
  slices — each ending at something runnable — and executed one at a time with a completion gate
  between steps.
- **FR-F20 — Verify phase [M4].** A step is verified by repository-declared back-pressure: build,
  tests, linters and any gate command the repository names. A step that cannot be verified is
  reported as unverified, never as passing.
- **FR-F21 — Review phase [M2].** A delivered pull request is reviewed by the existing reviewer, with
  reconciliation across rounds. The review model and prompt must differ from the build model and
  prompt.
- **FR-F22 — Human takeover [M3].** A person pushing to the branch or commenting on the pull request
  suspends automation for that work item until an operator resumes it.
- **FR-F27 — Fix a finding [M2].** An existing review finding can be dispatched as a run without a
  tracker. The finding already carries repository, commit, file, line, message and suggestion, so it
  is a complete task specification; the run's output is a branch and a pull request that the reviewer
  then reviews. This is what makes M0–M2 a product rather than infrastructure.

### 4.4 Autonomy, gates and policy

- **FR-F23 — Autonomy profiles [M3].** An operator defines named profiles. A profile assigns each
  phase a mode of `auto`, `approve` or `off`, plus caps for runs, wall clock, spend and protected
  paths.
- **FR-F24 — Label-selected autonomy [M3].** A work item selects a profile by tracker label. A label
  may only select a profile at or below the repository's operator-set ceiling; a higher selection is
  clamped visibly, never silently, and never upward. A label applied by an actor outside the
  allowlist does not count.
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
  aggregate records milestones only (ADR-033).
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
| **M0** | Walking skeleton: dispatch → sandbox → harness → pushed branch | FR-F1..F4, F11, F13 |
| **M1** | Real lifecycle: events, cancel, salvage, watchdog, taxonomy, credential pool | FR-F5..F10, F12, F14 |
| **M2** | Fix a finding; deliver a pull request; the existing reviewer reviews it | FR-F21, F27 |
| **M3** | Work source, labels, autonomy profiles, gates, human takeover | FR-F16, F17, F22..F25 |
| **M4** | Specification, plan and verify phases | FR-F18..F20 |
| **M5** | Second harness arm; Kubernetes runtime arm | FR-F15 |
| **M6** | Entitlements, per-capability metering, factory telemetry | FR-F26 |

**M0–M2 is a shippable product on its own** — *the reviewer now fixes what it finds* — needing no
tracker, no plan engine and no gates. That is what makes the build order safe: the risky
infrastructure is paid for by a feature that stands alone.

## 7. Assumptions & constraints

1. The operator supplies model credentials and is the party bound by the model vendor's terms
   (ADR-030). Code Spire never resells model access.
2. Subscription-authenticated harness runs have **no per-token price**. The money cap is inert for
   them by design; the call-count cap and the quota-headroom check are the live controls (ADR-025
   predicted exactly this).
3. The Docker runtime arm requires access to a Docker socket, which is root-equivalent on the host.
   This is stated in `SECURITY.md` rather than mitigated away.
4. A repository's own toolchain must exist inside the sandbox or verification is impossible. That is
   the operator's image to build; Code Spire supplies the contract, not every stack.
5. Vendor terms for automated use changed twice during 2026. Every finding is recorded with its
   retrieval date and re-checked before a harness ships.

## 8. Success criteria

**M0** — a dispatched run against a real repository produces a branch on the real remote containing a
commit the agent wrote, with the container destroyed afterwards and no credential in any layer, log
or event.

**M1** — killing the control plane mid-run loses no completed work and creates no duplicate charge;
killing the sandbox mid-run yields a classified failure, not a stall; exhausting one credential
rotates to the next without re-charging the call.

**M2** — a work item produces a pull request that the existing reviewer reviews, and round two
reconciles the findings from round one.

**M3** — a ticket labelled at each of three profiles produces three visibly different journeys; a
label above the ceiling is clamped and says so; a label applied by an unlisted actor is ignored.

**M6** — a month of operation answers, from stored data alone: cost per merged pull request, autonomy
rate, issue-to-merge lead time, where runs die, and what each capability cost.

## 9. Traceability

| Requirement group | Decisions | Design |
|---|---|---|
| Run kernel | ADR-028, ADR-033 | [ARCHITECTURE.md](./ARCHITECTURE.md) |
| Execution layer | ADR-029, ADR-030, ADR-031 | [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) |
| Work items, phases, gates | ADR-032, ADR-035 | [AUTONOMY.md](./AUTONOMY.md) |
| Packaging and entitlement | ADR-034 | [PACKAGING.md](./PACKAGING.md) |
