# Software Factory — Phases, Autonomy and Gates

How much a work item is allowed to do on its own, who decides, and where a human sits.

The governing idea: **autonomy is a property of the work item, not of the deployment.** A typo fix
and a change to an authentication path are not the same risk, and forcing one setting on both means
either the typo waits for a human or the auth change does not.

---

## 1. The eight phases

```
intake ─► spec ─► plan ─► build ─► verify ─► review ─► deliver ─► land
```

| Phase | What happens | Output |
|---|---|---|
| **intake** | eligibility, labels, ceiling, actor allowlist, budget reservation | admitted or refused |
| **spec** | vague ticket → outcome, context, acceptance criteria | specification written back to the tracker |
| **plan** | specification → ordered steps, each a vertical slice | plan, ready for a gate |
| **build** | one sandboxed run per step | commits on a branch |
| **verify** | the repository's own back-pressure runs | pass / fail / **unverified** |
| **review** | the existing reviewer reviews the branch | findings, reconciled across rounds |
| **deliver** | push and open a pull request | pull request URL |
| **land** | merge and close the work item | merged, or handed to a human |

### Why steps are vertical slices

Models build **horizontally** — the whole data layer, then the whole API, then the whole UI — so
nothing is runnable until thousands of lines exist, and by then re-steering is expensive because the
decisions are already embedded. A **vertical slice** ends at something that can be executed: a stub
endpoint that returns, a screen that renders, a test that runs.

This is what makes `verify` meaningful. Without it, verification happens once at the end, which is
the same as not having it.

### Why `unverified` is a distinct outcome

A step whose tests could not be run — missing toolchain, missing service, missing fixture — must
report **unverified**, never *passing*. Zero failing tests and zero runnable tests produce the same
green if the distinction is not modelled. This project has already shipped one bug of exactly this
shape, where a review that produced nothing was indistinguishable from a clean review.

## 2. Autonomy profiles

A profile is **a vector, not a level**: a gate mode per phase, plus caps.

```yaml
# operator configuration — stored in the registry, NOT in a repository file
factory:
  ceiling: assisted                       # no work item in this repository may exceed this
  profiles:
    suggest:
      spec: auto
      plan: auto
      build: off
      deliver: off
      land: off
    assisted:
      spec: auto
      plan: approve                       # a human approves the plan
      build: auto
      verify: auto
      review: auto
      deliver: draft_pr
      land: approve
    autonomous:
      spec: auto
      plan: auto
      build: auto
      verify: auto
      review: auto
      deliver: pr
      land: auto_if_green                 # top rung; unwired until explicitly enabled
  labels:
    "spire:suggest":   suggest
    "spire:assisted":  assisted
    "spire:auto":      autonomous
  caps:
    maxRunsPerItem: 5
    maxStepsPerPlan: 20
    maxWallClock: 2h
    maxCostUsd: 20
    maxCallsPerItem: 40                   # the live cap on an unmetered deployment
    protectedPaths: ["**/security/**", ".github/**", "deploy/**"]
```

Gate modes are `auto` (proceed), `approve` (wait for a human) and `off` (do not run this phase).

## 3. The three rules that make labels safe

A label is **untrusted control** arriving from a system Code Spire does not administer. Fencing it as
untrusted *data* — the defence used for ticket bodies, review comments and `.codespire` — does not
help, because control is not quoted into a prompt; it is obeyed.

### Rule 1 — the ceiling is operator-owned and does not live in the repository

Profiles, the ceiling and the label mapping live in the registry, not in `.codespire`.

This follows a distinction the project already draws: a per-repository **prompt** is an
operator-owned change to the reviewer's instructions, while `.codespire` is contributor-owned
**data** that can only add text into a fenced slot. Autonomy is instructions with authority attached.
It belongs on the operator side.

### Rule 2 — a label may only lower

If a label names a profile above the repository's ceiling, the selection is **clamped to the
ceiling** and the clamp is recorded as a timeline entry and an attention row. Visibly, always; never
silently, and never upward.

### Rule 3 — the labeller must be allowed

Anyone with tracker write can apply a label, so the label is an authorization decision made outside
Code Spire's trust boundary. The existing per-provider author allowlist is reused: **a label applied
by an actor outside the allowlist does not select a profile.** Without this rule, a drive-by
contributor opens an issue, labels it `spire:auto`, and the factory writes and merges their code
using the operator's credentials.

### The rule these three generalise

All three are instances of ADR-035:

> Repository-supplied configuration may **narrow** behaviour. It may never redirect where compute or
> credentials go, and it may never widen authority.

The same rule already explains why `.codespire` is read from the target branch rather than the
reviewed commit, why Codex ignores a repository's own `model_provider` setting, and why a per-repo
agent image must come from an operator allowlist.

## 4. Gates

### A gate is a durable state, not a blocking call

An `approve` phase persists a `work_item_gate` row. Nothing waits in memory, nothing holds a
connection, and a control-plane restart loses nothing.

An open gate appears in the **attention panel** — the existing one, whose contract is that every row
is a condition true right now, so resolving the gate removes the row with nothing to dismiss.

### Three answer channels

A human should never have to learn a new tool to unblock work:

| Channel | Shape |
|---|---|
| **Dashboard** | Approve / reject with a note |
| **Tracker** | A comment or a label on the ticket, from an allowlisted actor |
| **Pull request** | Approving the review approves the `land` gate |

All three write the same `GateResolved` event; the channel is recorded as an attribute.

### Gates expire

A plan awaiting approval for thirty days is dead work holding a budget reservation. Expiry is a
normal terminal state — `WorkItemRefused(gate_expired)` — with its own status, not a failure and not
a silent drop. Expiry windows are per-profile.

### Human takeover

A person pushing to the branch or commenting on the pull request sets `human_takeover` and suspends
automation for that work item until an operator resumes it.

The reasoning is not politeness. An agent that races a human produces conflicting commits, duplicate
review requests and a reviewer bot arguing with a maintainer in a public thread. The prior art is
explicit that a repository's reputation is a safety control, not a growth throttle.

## 5. Refusals

Every refusal is a **first-class terminal state with its own reason**, in the same vocabulary shape
as ADR-025's `CapRefusal`: a reason, a timeline detail, and an operator note.

| Reason | Meaning |
|---|---|
| `not_eligible` | no matching label, or the phase is `off` in the selected profile |
| `ceiling_clamped` | informational — the item ran at a lower profile than its label asked for |
| `labeller_not_allowed` | the label was applied by an actor outside the allowlist |
| `entitlement_missing` | the capability is not enabled for this deployment |
| `credentials_exhausted` | the whole pool is out of quota; the row names when capacity returns |
| `budget_exceeded` | a spend, call-count or wall-clock cap would be crossed |
| `gate_expired` | a human approval was never given |
| `protected_path` | the change touches a path the profile does not permit |

**These states must reach the UI's status union, its label map, its pipeline renderer and its chip
filter in the same change that introduces them.** A status the union does not know arrives as runtime
JSON and renders through the default branch — which is the *success* branch — while `tsc` has nothing
to check and every suite stays green. That has happened twice in this codebase; the second time, a
refused review rendered as five green segments under "done".

## 6. The review phase, and why it is not self-review

The factory's pull request is reviewed by the reviewer that already exists, with ADR-019
reconciliation handling round two.

**Configuration rule: the review model and prompt must differ from the build model and prompt.** A
model reviewing its own output shares its blind spots — if it could tell good code from bad, it would
have written the good version. The prior art is unanimous on this: independent reviewers on different
harnesses, and adversarial review with a second model, precisely because they find different things.

## 7. The measurement loop

```
work item → agent builds → PR → reviewer finds N findings
                                       │
                             agent fixes → reconcile → verdicts
                                       │
                    review_finding: raised / resolved / dismissed / acknowledged
                                       │
                     analytics + learned memory → better prompts, better profiles
```

Every finding is already stored per round with a verdict, dismissal rates are already computed, and
learned preferences already exist. What the factory adds is the **origin**: this pull request was
written by an agent, at this profile, by this harness, at this cost.

That join — *an agent wrote it × the reviewer found N × a human dismissed M × it cost X* — is what
tells an operator whether raising a repository's ceiling is safe. It is also the honest answer to the
question every autonomy setting begs, and it comes from data the deployment already collects.

## 8. Metrics

Adopted from the observed prior art because they are the right ones:

| Metric | Definition |
|---|---|
| **Cost per merged pull request** | the headline number; everything else explains it |
| **Autonomy rate** | merged with no steer, no re-run and no human commit |
| **Issue → merge lead time** | median, from admission to merge |
| **Where runs die** | a closed failure-cause discriminator, recorded as data |
| **Where the time goes** | per-phase medians: queue wait, agent work, verify, review wait, merge |

The failure taxonomy is worth copying in full: *provider error, dropped commit, sandbox lost, evicted,
finalize not posted, finalize failed, no model response, out of memory, sandbox unreachable, timed
out.* In the observed deployment the top cause was **provider error** and the second was **dropped
commit** — which is precisely why `finalize` and `destroy` are separate operations.
