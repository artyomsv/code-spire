# Software Factory — Architecture

> Extends [`../ARCHITECTURE.md`](../ARCHITECTURE.md). The event-driven, plugin-first core is
> unchanged; the factory adds one deployable, three seams and a second event tier.

---

## 1. Three planes

```
                     ┌──────────────────────────────────────────────┐
   POLICY PLANE      │  autonomy profiles · gates · entitlements     │
   what may run      │  spend caps · ceilings · protected paths      │
                     └───────────────────┬──────────────────────────┘
                                         │ read at dispatch
                     ┌───────────────────▼──────────────────────────┐
   WORK PLANE        │  WorkSource → work item → spec → plan         │
   what to run       │  phases, human takeover, tracker write-back   │
                     └───────────────────┬──────────────────────────┘
                                         │ RunRequested
                     ┌───────────────────▼──────────────────────────┐
   RUN PLANE         │  workspace → sandbox → harness → reap → push  │
   how it runs       │  events, cancel, steer, salvage, watchdog     │
                     └──────────────────────────────────────────────┘
```

The planes are not services. They are ownership boundaries: the policy plane decides, the work plane
sequences, the run plane executes. Only the run plane touches a sandbox, and it never decides
anything a human would call a policy.

## 2. Services

| Service | Today | Added |
|---|---|---|
| `spire-gateway` | webhook ingress → `cs.integration` | tracker webhooks (issue labelled, issue commented) on the existing keyed registry edge |
| `spire-orchestrator` | deciders, sagas, event store, dashboard APIs | `WorkItemLifecycle` decider, `RunSaga`, gate handling, entitlement check |
| `spire-review-worker` | commands → SCM/LLM adapters → results | unchanged (see note below) |
| **`spire-run-worker`** | — | **new**: the only component that opens a sandbox |

`spire-run-worker` is a separate deployable for three reasons, each of which independently decides it:

1. **Resource profile.** A run holds tens of gigabytes of disk and an hour of wall clock. The review
   worker is a short, ordered, blocking consumer.
2. **Blast radius.** The run worker needs a Docker socket or Kubernetes API access. That privilege
   must not sit in the process that posts review comments.
3. **Poison isolation.** One stalled run must not stall every review. The project has already paid
   for this exact failure: a slow LLM call once outran the SmallRye ack threshold and stalled a
   consumer that re-stalled on every restart and needed a manual consumer-group seek. It was never
   dead-lettered — the manual seek was necessary *because* the record never reached `cs.dlq`.

## 3. Seams

Each follows the house style: provider-neutral DTOs, capability flags read by the domain, one
registry resolved at boot, loud failure on an unknown selection, and a build check that fails on a
boundary-crossing name.

### 3.1 `HarnessAdapter` — what runs the agent

```java
public interface HarnessAdapter {
    HarnessType type();
    HarnessCapabilities capabilities();          // streaming, cancel, steer, resume, structuredOutput
    List<String> command(HarnessInvocation inv); // argv, no shell
    Map<String,String> environment(HarnessInvocation inv);
    Optional<RunEvent> parse(String line);       // one stdout line → normalized event, or empty
    TerminalOutcome classify(int exitCode, RunEventSummary seen);
    Optional<UsageReport> usage(RunEventSummary seen);  // empty = UNKNOWN, never zero
    void steer(HarnessSession s, String instruction);   // capability-gated
}
```

Two contract rules that are not obvious:

- **`usage()` returning empty means `UNKNOWN`, never zero.** ADR-023 exists because four separate
  places turned *unknown* into *zero*. A harness whose usage shape is unrecognised must arrive as
  unpriceable, not as free.
- **`command()` returns argv, never a shell string.** A prompt is untrusted text from a tracker.

### 3.2 `RunRuntime` — where it runs

```java
public interface RunRuntime {
    RuntimeType type();
    RuntimeCapabilities capabilities();   // networkPolicy, resourceLimits, steering, archival, gc,
                                         // nativeSidecar (k8s >= 1.29) — see RUN-TOPOLOGY.md §3
    RunHandle create(RunSpec spec);       // workspace, image, injected creds, limits
    Stream<RunEvent> attach(RunHandle h);
    void cancel(RunHandle h);
    Finalization finalize(RunHandle h);   // salvage BEFORE teardown — never merged with destroy
    void destroy(RunHandle h);
    List<RunHandle> discoverOrphans();    // for the watchdog
    Duration drainWindow();               // how long salvage may hold its caller after the agent
                                         // exits; the worker's ack budget adds it to the wall clock
}
```

Arms: **`docker`** (M0) and **`kubernetes`** (M5). Domain code branches on `capabilities()`, never on
`type()`.

> **A run is not one container.** ADR-039 makes it a three-part unit — an init clone, the agent as
> the main container, and the publisher as a sidecar — sharing one ephemeral volume and no storage
> outside the pod. The worker holds no workspace and runs no git. **[RUN-TOPOLOGY.md](./RUN-TOPOLOGY.md)
> is the authority on this**; the bind-mounted sketch that used to be in §7 is superseded.

`finalize` and `destroy` are two methods on purpose. Warren's second most common failure across 44
failed runs was **dropped commit** — the agent did the work and the container died with it.

### 3.3 `WorkSource` — where work comes from

```java
public interface WorkSource {
    WorkSourceType type();
    WorkSourceCapabilities capabilities();   // supportsTransitions, supportsPlans, supportsLabelAudit
    List<WorkItemRef> candidates(WorkQuery q);
    WorkItem fetch(WorkItemRef ref);
    void comment(WorkItemRef ref, String body);
    void transition(WorkItemRef ref, String state);

    /** Labels WITH the actor who applied each one. A label whose applier is unknown carries
     *  {@code Actor.UNKNOWN} and selects no autonomy profile — never a silent fallback. */
    List<LabelEvent> labelEvents(WorkItemRef ref);
}

record LabelEvent(String label, Actor appliedBy, Instant at, Origin origin) {}
enum Origin { WEBHOOK, AUDIT_TRAIL, UNATTRIBUTED }
```

`labelEvents` replaced a `Set<String> labels(ref)` after a review showed the safety rule built on it
was unimplementable. A set of strings has no author, and FR-F24 must know who applied a label. Only a
webhook names a sender; a label found by polling needs the tracker's own audit trail (GitHub's timeline
API, Jira's changelog), which `supportsLabelAudit` declares. Where neither is available the label is
`UNATTRIBUTED` and **selects nothing** — the honest degradation, rather than quietly enforcing the rule
only for labels a webhook happened to witness.

Arms: GitHub Issues, GitLab Issues, Jira — reusing the HTTP clients the `spire-context-*` modules
already have. Same hosts, same registry, wider rights (see [PACKAGING.md](./PACKAGING.md) §Knowledge
vs Build).

### 3.4 `PullRequestSink` — the port that does not exist yet

**There is no `Forge` type in this codebase.** An earlier draft of these documents said M2 would open
pull requests "via the existing `Forge` seam"; that name was imported from prior art and does not
appear in a single Java file here. The real SCM ports are `ScmIngress`, `DiffSource`, `CommentSink`,
`ThreadSource`, `IdentitySource` and `PrUrlParser`, and **none of them can create a pull request** —
nothing in Code Spire ever has.

So M2 owns real work, not wiring:

```java
public interface PullRequestSink {          // new port, three implementations
    PullRequestRef open(RepoRef repo, String head, String base, PrBody body);
    Optional<PullRequestRef> findByHead(RepoRef repo, String head);   // idempotency
}
```

plus **git-push credentials**, which are also new: today the registry token is brokered per command
for API calls only, and nothing in the system has ever pushed a commit. Whether that token doubles as
the push credential is decided at M2, per forge.

## 4. Identity

### 4.1 The factory's own identity

The factory pushes and opens pull requests as a **dedicated machine account**, registered separately
from the review bot (ADR-038).

The alternative fails on inspection. The only SCM credential a deployment holds today resolves to the
review bot, so a factory pull request would be bot-authored — and `IntegrationSaga` gates pull-request
events on the per-provider author allowlist (*"unlisted authors never get touched"*). On any
deployment with a non-empty allowlist, which is what this design's own threat model encourages, the
reviewer would **silently skip every factory pull request**, defeating M2 entirely. Allowlisting the
bot to fix that grants it allowed-author authority on `/review`, `/finding` and `/fix` — the bot could
command itself, which is the widening ADR-036 forbids.

`factory_run` records the identity it pushed as, and the review row carries a **factory-authored
attribute**. Neither is inferred from an account name: an account can be renamed, reassigned or
shared, and an attribute written at authorship cannot drift. Same reasoning that made `pr_state` its
own column and `origin` a field on a conversation-derived finding.

### 4.2 Run and work-item ids — derive, never register

`ReviewIds.parse` exists because an in-memory registry loses everything on restart. The same rule
holds, and the platform goes into the key **from day one**:

```
workItemId = f(scmType, workspace, slug, workSourceType, issueId)
runId      = f(workItemId, attempt)
```

The charge ledger currently keys on a `reviewId` carrying no provider — an open tech-debt entry,
because one workspace name registered on two SCMs sums two unrelated pull requests. `code_symbol`
learned this and keys `scmType:workspace/slug`. New tables do not inherit the older bug.

## 5. Messaging — the second event tier

One agent run in the observed prior art emitted **858 events**. That volume cannot ride the existing
result topic, and must never reach the event store: ADR-010 makes the aggregate the single writer of
domain events, and flooding it with `tool_execution_start` would wreck replay and encryption cost
alike.

| Tier | Transport | Store | Retention | Purpose |
|---|---|---|---|---|
| **Run stream** — reasoning, tool_use, tool_result, output, state | new topic `cs.run-events` | `run_event` table, bounded | short TTL | live tail, debugging, transcript |
| **Domain events** — `RunStarted`, `RunFinished`, `BranchPushed`, `GateOpened`, `GateResolved`, `WorkItemCompleted` | existing `cs.events` | event store | durable | the aggregate's truth, replayable |

Only **milestones** are promoted. This mirrors the split the prior art makes between a broker for
live tailers and a lifecycle bus for durable consumers, and it is ADR-011/ADR-014 discipline applied
one level up.

Topics after the change: `cs.integration`, `cs.commands`, `cs.events`, `cs.results`,
**`cs.run-commands`**, **`cs.run-control`**, **`cs.run-results`**, **`cs.run-events`**, `cs.dlq`.
Review topics are keyed by `reviewId`; run topics by `runId`.

### 5.1 The run worker cannot inherit the review worker's consumption model

The review worker's `commands-in` channel is ordered and blocking, with `max.poll.records` pinned to
`1`, a queue factor of `1`, and a 900-second unacknowledged-record ceiling that `LlmTimeoutBudget`
refuses to start below. An hour-long run breaks all three assumptions, and one consequence is fatal
rather than merely awkward:

> **A `CancelRun` keyed to the same aggregate lands on the same partition as the `ExecuteRun` it is
> meant to cancel — so it would be consumed only after that run finished.** Cancel that cannot
> cancel.

**Run commands also cannot ride `cs.commands` at all**, for a reason found while grounding the M0
plan: `ActionCommand` declares `String reviewId()` as a mandatory member of the sealed hierarchy, and
a run has a `runId`. Putting a run id behind a method named `reviewId()` is the shape where a name
lies. So run dispatch gets its own sealed type and its own topic:

| | Review | Run |
|---|---|---|
| type | `ActionCommand` (`reviewId()` mandatory) | **`RunCommand`** (`runId()`) |
| dispatch topic | `cs.commands` | **`cs.run-commands`** |
| control topic | — | **`cs.run-control`** |
| results topic | `cs.results` | **`cs.run-results`** |

And the semantics are specified rather than inherited:

| Concern | Review worker | Run worker |
|---|---|---|
| ack | after processing | **on receipt**, once `run_claim` is written |
| idempotency unit | the unacked record | **the `run_claim` row**, sole mechanism |
| liveness | the blocking consumer | `factory_run` state + `run_lease` heartbeat |
| cancel / steer | n/a | **`cs.run-control`**, consumed by a non-blocking listener beside the executor |

Acking on receipt moves the redelivery guarantee from Kafka to the claim row, which is why FR-F10's
intent journalling matters more here than it does for a review: after early ack, a lost dispatch
response cannot be recovered by redelivery, so an ambiguous outcome must fail closed into
`dispatch_uncertain`.

## 6. Command and event vocabulary (sketch)

Full catalogue lands in `../CONTRACT.md` at implementation time. The shape:

| Kind | Names |
|---|---|
| Integration events (gateway) | `WorkItemLabelled`, `WorkItemCommented`, `WorkItemClosed` |
| Commands (orchestrator → run worker) | `PrepareWorkspace`, `ExecuteRun`, `CancelRun`, `SteerRun`, `FinalizeRun` |
| Results (run worker → orchestrator) | `RunStarted`, `RunProgressed`, `RunFinished`, `BranchPushed`, `RunFailed` |
| Domain events (aggregate) | `WorkItemAdmitted`, `SpecDrafted`, `PlanProposed`, `StepDispatched`, `StepVerified`, `GateOpened`, `GateResolved`, `PullRequestOpened`, `WorkItemCompleted`, `WorkItemRefused` |

`WorkItemRefused` carries a discriminated reason, in the same vocabulary shape as ADR-025's
`CapRefusal`: ceiling clamp, unlisted labeller, entitlement missing, credentials exhausted, budget
exceeded, gate expired.

## 7. Data

New tables, in the schema of the service that owns them (schema-per-service, ADR-011).

**`orchestrator` schema**

| Table | Holds | Notes |
|---|---|---|
| `work_item` | run bookkeeping per `(work_source, repo, issue_id)` | **not** an issues mirror — no title, no body, no status of the ticket itself |
| `work_item_gate` | one row per open or resolved approval | expiry timestamp, resolver, channel |
| `factory_run` | read model: state, phase, harness, runtime, timings, outcome, failure cause | drives the UI |
| `run_event` | bounded transcript | TTL'd; encrypted where it may quote source (ADR-011 boundary) |

**`worker` schema**

| Table | Holds |
|---|---|
| `run_claim` | idempotency claim per `(run_id, slot)` — same shape as `comment_idempotency` |
| `run_lease` | sandbox ↔ workspace, **plus owner id and heartbeat** — see below |
| `harness_credential_state` | pool member health: available / rate-limited-until / rejected / disabled |

**The orphan definition, because "somebody's job" is not a design.** With more than one run-worker
replica on one Docker daemon or in one namespace, `discoverOrphans()` enumerates *every* sandbox,
including a sibling's healthy hour-long run. Reap eagerly and the watchdog kills live work — worse
than the leak it prevents; reap lazily and an eviction leaks forever. So:

> An **orphan** is a sandbox whose `run_lease` row is absent, or whose lease heartbeat is older
> than N missed intervals. Reaping an orphan always runs `finalize` (salvage) before `destroy`.

Leases carry `owner_id` and `heartbeat_at`; a live replica renews, a dead one stops. ADR-024 needed
six enforcement paths because no single choke point saw them all — this is the same shape, and it
needs stating rather than assuming.

**Changed: `llm_charge` needs more than two columns.**

The first draft said the ledger "gains `capability` and `credential_ref`", which hid real schema work.
The table's spine is review-shaped in three places, all verified: `review_id TEXT NOT NULL` (a run has
no reviewId); `CHECK (kind IN ('REVIEW','RECONCILE','FOLLOWUP'))`, whose own comment notes that an
unrecognised literal *dead-letters the result at INSERT time*; and `call_ref` identity derived from
reviewId + slot + commit.

The shape, decided rather than deferred:

```sql
ALTER TABLE llm_charge RENAME COLUMN review_id TO subject_id;
ALTER TABLE llm_charge ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD COLUMN capability   VARCHAR(16) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD COLUMN credential_ref TEXT;
-- kind CHECK extended: REVIEW | RECONCILE | FOLLOWUP | SPEC | PLAN | BUILD | FIX
-- subject_kind CHECK: REVIEW | RUN
```

- **`subject_id` + `subject_kind`, not a nullable second column.** One key with a discriminator keeps
  `UNIQUE (call_ref, token_type)` meaningful and every existing read a one-line change. Putting a run
  id into a column named `review_id` was rejected outright: that is the shape where a name lies, and
  the next reader inherits the lie.
- **`kind` gains four values, and the CHECK stays.** Its own migration comment explains why the CHECK
  exists — a typo'd literal would otherwise dead-letter the result at INSERT — so extending it is
  mandatory, not cosmetic.
- **`CallRefs` gains a run form:** `run:{runId}:{seq}`. Two deliberate departures from what this
  section originally specified, both settled when the first real caller landed in M1:
  - **No separate `{attempt}` segment.** `RunIds` already ends a run id with its attempt, so a
    second copy in the key could disagree with the first and pin the disagreement into the ledger.
    The property that matters is unchanged and still holds: a genuine re-run keys differently while
    a redelivery reproduces the key exactly, which is what `UNIQUE (call_ref, token_type)` then
    discards — the same distinction `ReviewRuns` draws for reviews.
  - **`seq` is the constant `agent`, not a per-call index.** A run IS one charge: the agent makes
    many model calls inside its sandbox and the worker never sees them, so a finer grain would be
    invented rather than measured. The `total` spelling this section proposed for the aggregate
    case is therefore never produced.
- **The ten existing ledger reads are updated in the same migration**, and the archived-row filter
  rule is unchanged: per-subject reads filter `archived_at`, the spend-window read does not.

ADR-026 already flagged this shape when it noted that embedding spend had no `call_ref` scheme under
ADR-023.

`capability` and `credential_ref` join in the same migration, **at M0**, because they cannot be
backfilled and M0 already spends real money — the same lesson as `review_finding` shipping with no
backfill.

## 8. Encryption boundary

Unchanged in principle (ADR-011, ADR-009): coordinates and classification in clear so they can be
grouped server-side; anything quoting source or a ticket body Tink-encrypted with AAD binding.

Applied to the new tables: `factory_run` timings, states and causes are clear; `run_event` payloads
are encrypted because a tool result quotes the repository. `work_item` holds identifiers only, so it
needs no encryption — which is also why it must not become a mirror.

## 9. Security posture, summarised

Full treatment belongs in `../SECURITY.md`. The four new boundaries:

1. **The sandbox.** Enforced by the runtime, not by a prompt. Network deny by default; egress
   allowlist for the model endpoint, the git host and package registries.
2. **The credential.** Injected at start, never in an image layer, redacted from every event.
3. **The image.** Selected from an operator allowlist. A repository may not name one (ADR-036).
4. **The label.** Untrusted *control*, not untrusted data — fencing does not help. Bounded by an
   operator ceiling and an actor allowlist (ADR-033).

The Docker arm's socket requirement is root-equivalent on the host and is documented as such rather
than mitigated away. The Kubernetes arm removes it.

## 10. What the build enforces

`spire-arch` today fails the build when a core module names an SCM or context provider outside a
reasoned allowlist. It is extended to **harness, runtime and work-source** names **in the same commit
as the first seam** — not after the first leak. The SCM version of this check found three real leaks,
one of which was a live defect returning 500 instead of 404.

**It is not "the same terms", and pretending otherwise would break the build on day one.** The
existing pattern is `(?i)(bitbucket|github|gitlab|jira|confluence)` — deliberately **unanchored**, with
a comment explaining that anchoring would miss `githubConfig`. The new names break that both ways:

| Name | Problem | Match mode |
|---|---|---|
| `codex`, `opencode`, `kubernetes`, `worksource` | none | substring, as today |
| `pi` | matches `spire`, `api`, `pipeline` — would fail the build on the project's own name | **qualified forms only**: `harness.pi`, `PiHarness`, `"pi"` as a whole quoted literal |
| `docker` | appears legitimately in deployment-adjacent core text | substring, with the deploy-facing files allowlisted |

The reduced sensitivity for short names is **recorded as a known limit**, not pretended away.
Switching to an import-graph scan was considered and rejected: the leak class that motivated a text
scan includes string literals, which no import graph can see.
