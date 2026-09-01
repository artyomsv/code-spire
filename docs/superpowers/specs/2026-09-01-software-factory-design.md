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

## 7. Open questions — all but one closed

The review promoted six items from "open question" to **must be answered before M0**, and the design
carried five more. Ten of the eleven are now decided; the tables live in
[`../../factory/ROADMAP.md`](../../factory/ROADMAP.md).

**One was answered by measurement rather than argument.** The review predicted Codex's Landlock
sandbox would be unavailable inside a container. Calling `landlock_create_ruleset` in an ordinary
Docker container returned ABI **v7** under the *default* seccomp profile on kernel 6.18 — the
prediction was wrong, and so was the draft that had assumed the opposite. Because one host proves
nothing about all hosts, the outcome is neither assumption: the runtime **probes at boot** and
declares a capability, and a missing inner sandbox is visible rather than silent.

**Two were closed by refusing the more elaborate option.** Entitlements are a registry entry, not a
signed licence file, because a cryptographic lock on software whose database the operator owns is
theatre and the FSL text is the real enforcement. And `spec` and `plan` are single model calls, not
harness invocations, because they edit no files and their context already arrives through
`ContextProvider` — which also makes them metered on a deployment where `build` is not.

**The last one was closed by asking the vendor.** The operator raised the subscription question with
**OpenAI support** and was told the use is permitted, recorded 2026-09-01 in ADR-030. The published
terms leave it open; a vendor support answer settles it for this deployment. The ticket reference was
not captured at the time, so the operator's support history is the artifact if it is ever challenged
— and because a support answer can be superseded as easily as a term, every arm also works on an API
key, so a reversal costs a credential change rather than a redesign.

**All eleven are therefore closed.** What remains before M0 is work, not questions.

## 8. Standing obligation

Vendor terms for automated use changed twice during 2026. **Before each harness arm ships, its terms
are re-read from the primary source and the finding is updated in
[`../../factory/EXECUTION-LAYER.md`](../../factory/EXECUTION-LAYER.md) §2 with a new retrieval date.**
The Codex subscription decision in particular rests on a confirmation obtained outside this research;
its provenance — who, where, when — belongs in `docs/DECISIONS.md` alongside ADR-030.

## 9. The adversarial review, and what it changed

An adversarial pass (fable-5) was run over the committed document set the same day. It produced
**24 findings — 2 critical, 6 high, 11 medium, 5 low** — and separately verified **17 load-bearing
claims about the existing codebase as correct**. Six of the most consequential were re-verified
independently against source before being accepted; all six held. All 24 are resolved in the current
text.

**The two critical findings each became an ADR**, because neither was a wording fix.

*The guaranteed output executed agent code outside the sandbox.* Pushing a branch triggers the
repository's CI **using the workflow files on that branch**, on an unsandboxed runner with repository
secrets. An injected agent modifies a workflow file; salvage pushes it before anything reviews it; CI
runs it. Every sandbox control in the design was bypassed by the kernel's own promise, and the only
defence present was `.github/**` appearing in a `protectedPaths` *example* with no statement of where
protected paths are enforced. → **ADR-036**: the push is gated, CI configuration is a floor no profile
may lower, and a failed salvage blocks teardown.

*The label-allowlist rule was unimplementable.* `WorkSource.labels()` returned a set of strings, which
has no author, so a label found by polling could never be attributed — the rule would silently apply
only to labels a webhook witnessed. And "reuse the existing per-provider author allowlist" compared
across identity spaces, since a Jira labeller has no SCM provider row. → **ADR-037** for the identity
half, `labelEvents` with actors for the attribution half, and a per-work-source allowlist.

**The most valuable class of finding was factual.** `Forge` — named in three places as "the existing
seam" that would open pull requests — **does not exist in this codebase**. It was imported from prior
art. The real ports cannot open a pull request and nothing here ever has, which means M2 owns a new
port, three adapters and git-push credentials that no milestone had scheduled. Two more of the same
kind: `llm_charge`'s spine is review-shaped and rejects unknown kinds at INSERT, so "gains two
columns" hid real schema work; and the `spire-arch` scan is deliberately unanchored, so adding `pi`
would have failed the build on the project's own name.

**Four findings were the design violating a rule it had just cited.** Gate modes declared a closed set
and then used three values outside it. `agent-image verify` would have converted *unknown* into
*verified*. Entitlement enforcement was described as one gate in one document and two in another.
Policy was read once at intake, in a document that cites ADR-024 needing six enforcement paths.

The generalisable lesson, recorded because it will recur: **a design document that names an existing
type is making a checkable claim, and citing a rule is not the same as applying it.** Both classes are
invisible to a careful re-read by the author and cheap for an adversarial reader to find.

## 10. Next step

Implementation planning for **M0** only — the walking skeleton in
[`../../factory/ROADMAP.md`](../../factory/ROADMAP.md). It proves the four unknowns that could sink
the project (sandbox, harness stream parsing, credential injection, branch push) in one vertical
slice, and everything after it is addition.
