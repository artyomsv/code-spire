# Software factory — design record

**Date:** 2026-09-01
**Status:** approved, not implemented
**Outcome:** `docs/factory/` (product documentation), ADR-028..ADR-035 (`docs/DECISIONS.md`)

This is the record of the session that produced the design: what was asked, what was researched,
which options were weighed, what was decided and why, and what remains open. The durable product
documentation lives in [`../../factory/`](../../factory/README.md); this file is the reasoning trail
behind it and does not repeat its content.

---

## 1. The ask

Extend Code Spire from an AI code reviewer into an **automated software factory**. Research the
running prior art the owner supplied — one live system, two repositories, seven talks — plus the wider
landscape, and produce a high-level architecture: building blocks, modules, and the feature set.

## 2. What was researched

Seven video transcripts, the Warren live instance and its full repository including twenty design
records, the machinist and waku-agent repositories, licence and star data for eleven agent harnesses,
the Unified Harness Protocol and Omnigent, the commercial background-agent landscape, sandbox
isolation technology, and vendor terms of service read from primary sources.

Findings and attribution are in [`../../factory/RESEARCH.md`](../../factory/RESEARCH.md). Vendor terms
are quoted with retrieval dates in
[`../../factory/EXECUTION-LAYER.md`](../../factory/EXECUTION-LAYER.md) §2.

## 3. The framing that settled the shape

Code Spire is roughly **70% of a factory already**, and holds the part nobody else has finished. It
has the provider-neutral SCM seam across three forges with a build check enforcing it, a priced charge
ledger with snapshotted rates, spend caps that refuse before the call, operator auth and RBAC, an
encrypted event store with sagas and idempotency claims, a findings corpus with analytics and learned
memory, an attention panel, and packaged Compose/Helm/kustomize deployment.

What it lacks is exactly one thing: **hands**. No workspace, no sandbox, no push. It reads and
comments; it never writes.

The closest prior art has the opposite shape — an excellent run kernel with no cost ledger, no spend
caps and no operator identity model, and a 17KB design record wanting precisely the
`trajectory × outcome × verdict` join that ADR-027 already ships half of.

That asymmetry is what made "extend the kernel" the right answer rather than "build a control plane
beside it".

## 4. Decisions taken, and the options rejected

| Question | Options weighed | Decision | Record |
|---|---|---|---|
| First cut | (a) give the reviewer hands within the PR loop; (b) full work-item factory | **(b)** — the owner chose the larger scope | ADR-028 |
| Topology | (a) extend the kernel; (b) separate control plane over HTTP; (c) extend the review worker | **(a)** | ADR-028 |
| Agent execution | (a) drive an existing harness CLI; (b) build our own loop on `LlmProvider` | **(a)** | ADR-029 |
| Work queue | (a) tracker is source of truth, no mirror; (b) own backlog | **(a)** | ADR-028 |
| Autonomy | (a) stop at the pull request; (b) wire auto-merge in v1 | **neither** — the owner proposed per-ticket autonomy by label, which was adopted | ADR-032 |
| First harness arm | (a) pi; (b) Codex | **(b) Codex**, pi second | ADR-029 |
| Auth mode | (a) API key only; (b) allow subscription where the vendor permits | **(b) for Codex**, on the owner's confirmation; API key everywhere else | ADR-030 |
| Images | (a) base image to inherit from; (b) conformance contract any image satisfies | **(b)**, after the owner raised enterprise policy images | ADR-031 |
| Harness interop | adopt UHP as the internal contract, or shape the seam so UHP is one arm | **one arm** | ADR-029 |

### Rejected with reasons

- **Warren's controller pattern.** Correct for a deliberately minimal kernel; here it would mean
  rebuilding auth, cost, events and UI to gain decoupling nobody asked for.
- **Extending `spire-review-worker`.** Three independent reasons, each sufficient — resource profile,
  privilege, and poison isolation. The project has already dead-lettered a consumer with a slow call.
- **Building our own tool loop.** Harness quality alone moves benchmark results by 20–30 percentage
  points on the same model; the loop is easy and the rest is a second product.
- **Adopting UHP internally.** An HTTP hop where the worker already owns the process.
- **A hosted sandbox vendor** (E2B, Daytona, Modal). A self-hosted product cannot require one, and
  cold-start latency is irrelevant for a workload measured in tens of minutes.

## 5. The owner's two contributions that changed the design

**Per-ticket autonomy.** Both options offered were deployment-wide. The owner's counter-proposal —
autonomy declared by the work item through labels, with the orchestrator deciding which phases need a
human — is better, and it forced two guards that a deployment-wide setting would never have needed:
an operator ceiling that a label can only lower, and an actor allowlist on the labeller. Without the
second, anyone with tracker write could grant themselves merge rights.

**Enterprise images.** The first image design was a base to inherit from. The owner's observation that
a company will need its own image for internal policy and access turned it into a **conformance
contract with a verify command** — which is strictly better, because it also covers the customer who
must build from an approved golden base and mirror into a private registry.

## 6. What the research produced that the design would otherwise have missed

- **`finalize` separate from `destroy`.** The observed failure data shows *dropped commit* as the
  second most common cause across 44 failed runs. Merging salvage into teardown throws completed work
  away.
- **The event-volume trap.** 858 events from a single run. Writing that into an event store built on
  a single-writer aggregate with encrypted payloads would have been discovered in production.
- **Back-pressure and vertical slices.** An unbounded agent invents work; the counterweight is
  deterministic and external. And models build horizontally, so nothing is testable until the end
  unless the plan forces otherwise.
- **A fourth instance of one rule.** `.codespire` target-branch reading, Codex ignoring
  repository-supplied `model_providers`, repo-named images, and autonomy labels are all the same
  rule. Naming it once (ADR-035) is what stops a fifth rediscovery.
- **`review_finding` is the corpus everyone else wants.** ADR-027 shipped the expensive half of a
  measurement loop the prior art lists as unscheduled.

## 7. Open questions carried forward

Tracked in [`../../factory/ROADMAP.md`](../../factory/ROADMAP.md) §Open questions:

1. Where the `spec` phase writes — tracker comment, repository file, or both.
2. Whether `verify` can fail a work item or only a step.
3. How entitlements are delivered — signed licence file, registry entry, or operator toggle.
4. Whether `plan` needs a harness invocation or is a single model call.
5. Where licence provenance for generated code is checked — a scanner in `verify`, a gate at
   `deliver`, or a report only.

## 8. Standing obligation

Vendor terms for automated use changed twice during 2026. **Before each harness arm ships, its terms
are re-read from the primary source and the finding is updated in
[`../../factory/EXECUTION-LAYER.md`](../../factory/EXECUTION-LAYER.md) §2 with a new retrieval date.**
The Codex subscription decision in particular rests on a confirmation obtained outside this research;
its provenance — who, where, when — belongs in `docs/DECISIONS.md` alongside ADR-030.

## 9. Next step

Implementation planning for **M0** only — the walking skeleton in
[`../../factory/ROADMAP.md`](../../factory/ROADMAP.md). It proves the four unknowns that could sink
the project (sandbox, harness stream parsing, credential injection, branch push) in one vertical
slice, and everything after it is addition.
