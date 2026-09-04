# The Software Factory

> **Status: M0 and M1 delivered; M2 next.** Written 2026-09-01 as the agreed design for extending
> Code Spire from a reviewer into an automated software factory, after a research pass over the
> running prior art. **M0** (the walking skeleton, PR #95, 2026-09-02) and **M1** (the lifecycle,
> PR #96, 2026-09-03) are built and merged; M3–M6 remain design. The build order and what each
> milestone actually taught are in [ROADMAP.md](./ROADMAP.md); the delivery log is in
> [`../HISTORY.md`](../HISTORY.md). **Read a design claim here against the code before relying on**
> **it** — this directory was written before any of it existed.

## What this is

Code Spire today **reads and comments**. It receives a pull-request webhook, fetches a diff,
assembles context, calls a model, and posts findings it then tracks across rounds. It never writes
to a repository, never runs a command, and never holds a workspace.

The factory gives it **hands**. A work item — a tracker issue — becomes a specification, then a
plan, then one or more agent runs inside an isolated sandbox, then a pushed branch, then a pull
request that the existing reviewer reviews. A human approves at whichever phases the work item's
own autonomy label says they should.

```
tracker issue
     │
     ▼
 intake ─► spec ─► plan ─► build ─► verify ─► review ─► deliver ─► land
     │       │       │       │        │         │          │         │
     └───────┴───────┴───────┴────────┴─────────┴──────────┴─────────┘
              each phase is a gate the work item's autonomy profile
              may set to auto, approve (human), or off
                                  │
                                  ▼
              guaranteed output: a branch that PASSED the push gate
              everything past it is policy, not the kernel
```

## The one-sentence division of labour

> **Code Spire executes agents. Policy decides what runs, how far it may go, and what it costs.**

The kernel owns the run lifecycle. Autonomy, budgets, gates and merge authority are policy read at
dispatch time — never inferred from a credential, never granted by a repository.

## What already exists, and what is new

The factory is not a new product bolted on. Most of the hard parts are shipped.

| Already shipped | Genuinely new |
|---|---|
| Provider-neutral SCM seam, 3 forges, build-enforced (ADR-020) | A **workspace** and a **sandbox** |
| Priced charge ledger + spend caps (ADR-023, ADR-025) | A **harness** — an agentic tool loop, not one LLM call |
| Encrypted provider registry, SSRF-guarded | **Writes**: branch, push, pull request |
| Operator auth + RBAC (ADR-022) | A **work source** — the tracker as a queue, not as context |
| Event store, sagas, idempotency claims, DLQ | **Gates** — durable human approval points |
| Findings corpus + analytics + learned memory (ADR-027) | **Autonomy profiles** driven by ticket labels |
| Attention panel, packaged deploy (Compose/Helm/kustomize) | A second, high-volume **run event** tier |

The single largest asset is the one nobody else has: **the reviewer reviews the factory's own
output**, and ADR-019 reconciliation already handles the second round. That closes a measurement
loop — *an agent wrote this × the reviewer found N × a human dismissed M × it cost X* — that the
closest prior art (Warren's corpus-flywheel record) describes at length and lists as unscheduled.

> **Reviewed 2026-09-01.** An adversarial pass over this document set produced 24 findings — 2
> critical, 6 high, 11 medium, 5 low — and verified 17 load-bearing claims about the existing codebase
> as correct. All 24 are resolved in the current text; the two critical ones added ADR-037 (the push
> gate) and ADR-038 (the factory identity). The review record is in the design spec.

## Reading order

| Document | Answers |
|---|---|
| [PRD.md](./PRD.md) | Who it is for, functional requirements FR-F1..F32, NFRs, success criteria |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Planes, seams, services, topics, identity, data tiers |
| [MODULES.md](./MODULES.md) | Every new module: purpose, interface, dependencies, licence |
| [AGENT-IMAGE-CONTRACT.md](./AGENT-IMAGE-CONTRACT.md) | The published contract any agent image may satisfy, and why its report separates what was verified from what the image merely declares |
| [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) | Harnesses, vendor terms with quotes, credential pooling, agent images |
| [RUN-TOPOLOGY.md](./RUN-TOPOLOGY.md) | **What runs where.** Measured spike results, the pod layout, the handoff protocol, continuous checkpointing, and how the gate actually works |
| [AUTONOMY.md](./AUTONOMY.md) | The eight phases, autonomy profiles, gates, and the label threat model |
| [PACKAGING.md](./PACKAGING.md) | Product capability packs, entitlements, per-capability metering |
| [RESEARCH.md](./RESEARCH.md) | The prior art this design is built on, with sources |
| [ROADMAP.md](./ROADMAP.md) | M0–M6 build order, and what is deliberately not built |

Decisions are recorded as **ADR-029 through ADR-040** in [`../DECISIONS.md`](../DECISIONS.md),
alongside every earlier decision. The design record for the session that produced this directory is
[`../superpowers/specs/2026-09-01-software-factory-design.md`](../superpowers/specs/2026-09-01-software-factory-design.md).

## The rule that recurs

Two existing defences — one this project built, one found in another vendor's tool — turn out to be
the same rule, and the factory needs it twice more:

- `.codespire` is read from the **target branch, never the reviewed commit** — a pull request must
  not rewrite the instructions of the reviewer judging it.
- Codex CLI ignores `model_provider` in a repository's own config file, in its own words *"to
  prevent repositories from secretly changing the machine's model provider."*
- An agent image named by a repository would decide where the operator's credentials get injected.
- An autonomy label applied by anyone with tracker write would decide what the factory may merge.

> **Repo-supplied configuration may narrow behaviour. It may never redirect where compute or
> credentials go, and it may never widen authority.**

This is ADR-036. Every new repo-readable setting is checked against it.
