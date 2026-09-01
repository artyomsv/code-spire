# Software Factory — Product Modules, Entitlements and Metering

How the platform divides into things a customer can buy, and what that forces on the code **now**
rather than later.

---

## 1. Two axes, deliberately not conflated

| Axis | Splits by | Carries |
|---|---|---|
| **Code modules** (`spire-*` Gradle modules) | technical seam | the licence — Apache-2.0 for SPI, libraries and adapters; FSL-1.1-ALv2 for the deployables (ADR-021) |
| **Product modules** ("capability packs") | customer-visible capability | the entitlement — what this deployment switched on |

One pack spans several code modules plus a worker plus UI screens plus read-model tables. The two
axes are orthogonal and both hold at once.

## 2. The six packs

| Pack | Contains | Status |
|---|---|---|
| **Core** | webhook ingress, event store, provider registry, auth and RBAC, dashboard shell, **cost ledger and spend caps**, attention panel | ships |
| **Review** | pull-request review, findings, conversation, reconciliation, prompt management, `.codespire` rules, code context, symbol index | ships |
| **Knowledge** | external connectors, read side: Jira, Confluence, GitHub Issues, GitLab Issues | ships |
| **Build** | workspace, sandbox, harness run, **spec, plan, verify**, branch push, pull request. Work item in → pull request out, **on demand** | new |
| **Autonomy** | triggers and schedules, patrol agents, unattended progression through gates, and the `land` rungs above `approve` | new |
| **Insight** | analytics, learned memory, audit export, per-author statistics, factory telemetry | partly ships |

**Core is the chassis and is not sellable alone.** Everything else assumes it.

### Why Build and Autonomy are separate packs

They look like one capability and are not:

- **Build** is *"I ask, it builds."* A human names the work and a human is present at every gate.
- **Autonomy** is *"it decides when to work, and proceeds without me."* Schedules, patrols, and
  unattended progression through gates.

That is a real value line — a team may want on-demand runs long before it wants overnight ones. It
is also the **scary** half, so gating it separately is a safety control that happens to also be a
price line. A deployment can hold Build without ever being able to start work by itself.

**The line is drawn at the human, not at the phase — a review found the first draft drawing it in the
wrong place.** Plan decomposition (FR-F19) was assigned to Autonomy while being scheduled at M4 as
ordinary work-item flow, which would have left a Build-only customer unable to handle any ticket
larger than one prompt. And "every rung above draft pull request" put `deliver: pr` in Autonomy,
crossing a purchase boundary in the middle of a single profile.

Corrected: **`spec`, `plan`, `verify` and `deliver` are Build** — they are how a work item becomes a
pull request, and a customer who bought "work item in, pull request out" has bought them. **Autonomy
is the absence of the human**: anything that starts work without a person asking (schedules, patrols),
and anything that passes a gate without a person answering it. Concretely, a Build-only deployment can
run every phase and every profile, but `land` is capped at `approve` and nothing dispatches on a
timer.

### Why Knowledge and Build share connectors but stay separate

Both touch Jira, GitHub Issues and GitLab Issues, through the same registry and the same
SSRF-guarded HTTP client. The difference is **rights, not transport**:

| | Knowledge | Build |
|---|---|---|
| read an issue as context | yes | yes |
| claim it, comment, transition it | no | yes |

Authority narrows per pack. A Knowledge-only deployment holds credentials that read; a Build
deployment holds credentials that write. That distinction is enforced at the adapter, not merely
documented.

## 3. Entitlements

### Two mechanisms, not one — and the first draft conflated them

| | The **entitlement gate** | **Credential rights** |
|---|---|---|
| Answers | may this deployment do this at all? | what can this credential reach? |
| Lives | **one place**: the saga, beside `SpendGate` | **per adapter** |
| Fails as | `entitlement_missing`, a first-class refusal | an absent method |

Saying both were "one gate" was a contradiction rather than a summary. They are different mechanisms
with different failure modes, and both are needed: a policy check can be bypassed by reaching the code
beneath it, and an absent method cannot. A Knowledge-tier tracker credential cannot transition an
issue because the adapter it is handed has no transition — not because a check declined.

**The gateway is a third surface and is honest about it.** It accepts tracker webhooks regardless of
pack, because refusing at ingress would make an entitlement change look like a broken integration.
The refusal happens at dispatch, where it can be explained.

### One gate, not scattered conditionals

`Entitlements` is a value type in `spire-contract`; **enforcement lives in the FSL services**. It is
read at exactly one place: in the saga, beside `SpendGate` and the priceability check, so that every
reason a dispatch was refused reads in one place.

This is not a stylistic preference. The codebase has twice paid to learn it: `SpendGate` exists so
that one money comparison cannot drift between the enforcement site and the attention row, and
`ProviderCircuits` exists so that health is decided once per host rather than per call site. A
capability check scattered as `if (hasBuild)` across handlers is the `hasPlot` anti-pattern that
another project in this space wrote an explicit rule against.

### A blocked capability is a refusal, not a crash

`entitlement_missing` is a first-class terminal state with its own status, its own timeline detail,
its own operator note and its own attention row — and it reaches the UI's status union in the same
change. An unentitled capability must be **invisible or clearly refused**, never broken, and never
rendered through the success branch.

### Licence and entitlement are orthogonal, and both must hold

FSL already prevents a competitor reselling the services as a hosted product. Entitlements decide
what the operator's own deployment switched on. They answer different questions and neither
substitutes for the other.

Placement follows from that: `Entitlements` as a type belongs in `spire-contract` (Apache-2.0,
framework-free) because commands carry it across the wire; **enforcement** belongs in the services,
because an Apache-2.0 module must not depend on a service module. This is the same constraint that
kept the LLM circuit breaker out of `spire-llm`.

## 4. Metering — the change that must happen now

Per-module pricing asks exactly one question: *what did this capability cost this deployment this
month?*

Answering it requires that every charge record **which capability caused it**. So `llm_charge` gains:

| Column | Holds |
|---|---|
| `capability` | `REVIEW`, `BUILD`, `AUTONOMY`, `KNOWLEDGE`, `INSIGHT` |
| `credential_ref` | which pool member paid, so an unmetered run is still attributable |

**This is cheap now and impossible to backfill.** A charge row that did not record its capability
cannot have one inferred later — the same lesson as `review_finding` shipping with no backfill, and
the same failure shape as ADR-023's four places where *unknown* silently became *zero*. If the column
is added a year from now, the first year of the answer is permanently lost.

### Unmetered runs still need attribution

A subscription-authenticated run has no price (see [EXECUTION-LAYER.md](./EXECUTION-LAYER.md) §3.2),
so per-capability **money** is null for it. Per-capability **call counts** are not. Both axes are
recorded, for the same reason `SpendGate` caps on both: a money-denominated total is inert on an
unmetered deployment, and a count is not.

## 5. What a pack boundary must satisfy

Before a capability becomes a pack, three things must be true. Any capability that cannot satisfy all
three is a feature of an existing pack, not a new one.

1. **A customer can describe it without describing the others.** "It reviews pull requests." "It
   builds tickets." "It decides when to work."
2. **It can be switched off and leave a coherent product.** Disabling Build leaves a working
   reviewer; disabling Core leaves nothing, which is why Core is not sold.
3. **It can be metered.** Its cost is attributable from stored data, not estimated by apportionment.

## 6. Deliberately not decided here

- **Price points, tiers and packaging names.** This document fixes the *boundaries* so that pricing
  is possible; it does not set prices.
- ~~How entitlements are delivered~~ — **decided: a registry entry the operator sets, like a
  provider.** Not a signed licence file. A signed file implies technical enforcement *against* the
  operator, which needs key management, offline validation and a revocation story — and it would be
  theatre here, because this is self-hosted source-available software whose actual enforcement is the
  FSL licence text and whose database the operator owns. Stating that plainly is better than shipping
  a lock that a `psql` session opens. Entitlements exist to make the product's shape legible and its
  billing honest, and they are recorded, auditable and inspectable for exactly that reason.
- **Whether Insight splits further** into analytics versus audit. It might; the metering column makes
  the question answerable with data instead of opinion.
