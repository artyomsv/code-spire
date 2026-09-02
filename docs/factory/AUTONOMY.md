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
| **spec** | vague ticket → outcome, context, acceptance criteria — *one model call, no sandbox* | specification commented back to the tracker |
| **plan** | specification → ordered steps, each a vertical slice — *one model call, no sandbox* | plan, ready for a gate |
| **build** | one sandboxed run per step — *harness* | commits on a branch |
| **verify** | the repository's own back-pressure runs — *harness* | pass / fail / **unverified** (fails the step, never the item) |
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

### How step N learns what step N-1 decided

Fresh context per step is the point — it keeps every run inside the model's good zone. But the
repository carries **what** was done, never **why**. Step 3 ("wire the API") needs step 1's choice
("envelope shape X over Y, because Z"), which is invisible in the diff and predates the plan.

So **each step's run ends by writing a structured summary** — decisions taken, alternatives rejected,
deviations from the plan — stored on the work item and prepended to the next step's prompt (FR-F31).
Without it, later steps re-derive or contradict earlier choices, and the completion gate has no record
to judge against.

The prior art names this problem and does not solve it — *"a killed script restarts from the beginning
unless the script owns checkpointing"* — and the same research praises a run ledger as shared memory
elsewhere. This is that ledger, applied where it was needed.

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

### Gate mode vocabularies are per phase

The first draft declared one closed set — `auto | approve | off` — and then used `draft_pr`, `pr` and
`auto_if_green` in the example above it. That is the closed-set-versus-runtime-value failure this
document set lectures about elsewhere, committed three paragraphs apart. The vocabularies are:

| Phase | Modes |
|---|---|
| `intake`, `spec`, `plan`, `build`, `verify`, `review` | `auto` · `approve` · `off` |
| `deliver` | `off` · `draft_pr` · `pr` |
| `land` | `off` · `approve` · `auto_if_green` |

`auto` proceeds; `approve` waits for a human; `off` does not run the phase.

**A phase omitted from a profile defaults to `off`, not to `auto`.** The `suggest` profile above omits
`verify` and `review`, and defaulting an unnamed phase to "proceed" would mean a profile grew new
autonomy every time a phase was added to the pipeline.

## 3. The four rules that make labels safe

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

### Rule 3 — the labeller must be allowed, and must be knowable

Anyone with tracker write can apply a label, so the label is an authorization decision made outside
Code Spire's trust boundary. **A label applied by an actor outside the allowlist does not select a
profile.** Without this rule, a drive-by contributor opens an issue, labels it `spire:auto`, and the
factory writes and merges their code using the operator's credentials.

A review found the first draft got both halves of this wrong.

**The allowlist is per work source, not the SCM author allowlist.** Reusing the SCM allowlist compares
across identity spaces: a Jira labeller has a Jira account id and no SCM provider row at all. This
project has already been bitten by that class — one workspace name registered on two SCMs cross-wired
them until `ReviewProviderResolver` disambiguated by stored provider type — so each work-source
registration carries its own allowlist of tracker actors.

**An unattributable label selects nothing.** A set of label strings has no author. Only a webhook
`labeled` event names a sender; a label discovered by **polling** — after downtime, on a backfill, on
the first scan of an existing backlog — has no actor unless the adapter reads the tracker's own audit
trail. So `WorkSource` exposes label *events with actors* (FR-F16a), and a label whose applier cannot
be established selects **no profile at all**. Degrading quietly to "webhook-observed labels only"
would leave the exact attack above working through every label the webhook did not witness — the rule
would look enforced and not be.

### Rule 4 — policy is re-resolved at every phase transition

Reading policy once at intake leaves a work item running for hours under a decision that may no longer
hold. Five concrete bypasses, all closed by a registry read per phase:

| Situation | Without re-resolution | With it |
|---|---|---|
| operator lowers the ceiling | in-flight items keep the old profile | next transition clamps |
| label removed mid-flight | automation continues | next transition finds no profile and stops |
| two profile labels at once | undefined which wins | **lowest wins** |
| profile definition edited | resolved gates proceed under an unknown version | the item carries the **version** it was admitted under; moving it is an explicit re-admission |
| issue transferred to another repository | identity key changes; item orphaned and re-admitted fresh | the item is **retired**, not silently continued |

ADR-024 had to enforce retirement at **six** separate paths because no single choke point saw them
all. The same is true here, and the read is cheap.

### The rule these rules generalise

All three are instances of ADR-036:

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

## 5. The push gate

A pushed branch is not inert. On every forge this project supports, pushing triggers the repository's
CI **using the workflow files on the branch being pushed**, on an unsandboxed runner, holding the
repository's secrets.

So the design's own guaranteed output was its largest hole. The agent's prompt contains untrusted
tracker text. An injected agent commits a modified `.github/workflows/*`. Salvage pushes it before
anything reviews it. CI executes it. Every sandbox control is bypassed by the kernel's own promise.

**Between commit and push, the run plane diffs the branch against its base and refuses a push that
touches a protected path.** A refused push preserves the workspace, classifies the failure, and raises
an attention row naming the paths. It is not a silent skip and it is not a teardown.

**CI configuration is a floor no profile may lower:**

```
.github/workflows/**   .gitlab-ci.yml   .gitlab/**
bitbucket-pipelines.yml   Jenkinsfile   .circleci/**
```

A profile may protect more. No profile may protect less. An operator who genuinely wants
agent-authored CI changes enables that per repository, outside the profile mechanism, having read what
it means.

**How the match works, decided rather than left to the implementer:**

- **Use the JDK's `java.nio.file.PathMatcher` with `glob:` syntax** — it already implements `**`,
  `*`, `?` and character classes, so the product gains no glob dialect of its own and no dependency.
  A first draft said "reuse `PathGlobs`"; that was wrong. `PathGlobs` does the **opposite** job — it
  maps a path *to* the group glob a learned preference is about (`src/foo/Bar.java` → `src/foo/**`).
  It cannot answer "does this path match this glob", which is what a gate needs.
- **Match the diff's changed-path set against the base**, including **both sides of a rename** and
  **deletions**. A deleted workflow file changes what CI does exactly as much as an edited one, and a
  rename that moves a file *into* a protected path is the obvious evasion.
- **The CI floor matches case-insensitively.** `.GitHub/workflows/x.yml` is a different path to git
  and the same path to a case-insensitive filesystem, and the forge will happily run it. Repository
  globs stay case-sensitive, because that is what an operator writing them expects.
- **A refusal names every blocked path**, in the timeline entry and the attention row, with status
  `push_gate_refused` — not "the push was blocked".

This mirrors the never-suppressed SECURITY floor in ADR-027: a learned preference may hide many kinds
of finding and may never hide a security one, because the evidence qualifying a group is itself
manufacturable. Same shape here — the input that would authorise the change is the input under
suspicion.

**The cost, stated plainly:** a run can do correct work and still deliver nothing, and an operator will
sometimes disagree with a refusal. That is the right trade against a failure mode of arbitrary code
execution on a runner holding production secrets.

## 6. Refusals

Every refusal is a **first-class terminal state with its own reason**, in the same vocabulary shape
as ADR-025's `CapRefusal`: a reason, a timeline detail, and an operator note.

| Reason | Meaning |
|---|---|
| `not_eligible` | no matching label, or the phase is `off` in the selected profile |
| `ceiling_clamped` | informational — the item ran at a lower profile than its label asked for |
| `labeller_not_allowed` | the label was applied by an actor outside the work source's allowlist |
| `label_unattributable` | the label's applier could not be established, so it selects nothing |
| `push_gate_refused` | the branch touches a protected path; the workspace is preserved |
| `salvage_failed` | commit or push failed; the workspace is preserved, not torn down |
| `fix_chain_exhausted` | this finding already has the maximum number of fix runs |
| `item_retired` | the tracker issue moved or was deleted, changing the identity it is derived from |
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

## 7. The review phase, and why it is not self-review

The factory's pull request is reviewed by the reviewer that already exists, with ADR-019
reconciliation handling round two.

**Configuration rule: the review model and prompt must differ from the build model and prompt.** A
model reviewing its own output shares its blind spots — if it could tell good code from bad, it would
have written the good version. The prior art is unanimous on this: independent reviewers on different
harnesses, and adversarial review with a second model, precisely because they find different things.

## 8. The measurement loop

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

**The loop is bounded, and that has to be stated rather than assumed.** A finding on PR-1 spawns a
fix, whose review raises a finding, which spawns another fix. Each hop sits inside its own caps and
the *chain* sits inside none of them — and a fix dispatched outside a work item has no item to count
against. So a fix run records the **finding id** it addresses, and dispatch refuses past N fix runs
for that finding (FR-F32), checked at the same choke point as `SpendGate`. One column, one check, and
the difference between a measurement loop and a money loop.

## 9. Metrics

Adopted from the observed prior art because they are the right ones:

| Metric | Definition |
|---|---|
| **Cost per merged pull request** | the headline number; everything else explains it |
| **Tokens (or runs) per merged pull request** | the same number's stand-in on an **UNMETERED** deployment, where cost is a flat subscription fee and a money figure says nothing. Without this substitute, an unmetered factory has no way to tell "getting better" from "getting busier" |
| **Autonomy rate** | merged with no steer, no re-run and no human commit |
| **Issue → merge lead time** | median, from admission to merge |
| **Where runs die** | a closed failure-cause discriminator, recorded as data |
| **Where the time goes** | per-phase medians: queue wait, agent work, verify, review wait, merge |

The failure taxonomy is worth copying in full: *provider error, dropped commit, sandbox lost, evicted,
finalize not posted, finalize failed, no model response, out of memory, sandbox unreachable, timed
out.* In the observed deployment the top cause was **provider error** and the second was **dropped
commit** — which is precisely why `finalize` and `destroy` are separate operations.

Three causes this design adds, each because it would otherwise arrive as something misleading:
**`push_gate_refused`** (correct work, deliberately not delivered — not a crash), **`blocked_egress`**
(a host the allowlist did not contain, named — not a mysterious build failure), and
**`credentials_exhausted`** (capacity, with a return time — not an error).
