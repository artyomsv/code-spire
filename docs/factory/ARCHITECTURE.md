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
| `spire-review-worker` | commands → SCM/LLM adapters → results | unchanged; later an optional `HarnessReviewer` review mode |
| **`spire-run-worker`** | — | **new**: the only component that opens a sandbox |

`spire-run-worker` is a separate deployable for three reasons, each of which independently decides it:

1. **Resource profile.** A run holds tens of gigabytes of disk and an hour of wall clock. The review
   worker is a short, ordered, blocking consumer.
2. **Blast radius.** The run worker needs a Docker socket or Kubernetes API access. That privilege
   must not sit in the process that posts review comments.
3. **Poison isolation.** One stalled run must not stall every review. The project has already paid
   for this exact failure: a slow LLM call once outran the SmallRye ack threshold and dead-lettered a
   consumer that then re-stalled on every restart.

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
    RuntimeCapabilities capabilities();   // networkPolicy, resourceLimits, steering, archival, gc
    RunHandle create(RunSpec spec);       // workspace, image, injected creds, limits
    Stream<RunEvent> attach(RunHandle h);
    void cancel(RunHandle h);
    Finalization finalize(RunHandle h);   // salvage BEFORE teardown — never merged with destroy
    void destroy(RunHandle h);
    List<RunHandle> discoverOrphans();    // for the watchdog
}
```

Arms: **`docker`** (sibling container, M0) and **`kubernetes`** (pod per run, M5). Domain code
branches on `capabilities()`, never on `type()`.

`finalize` and `destroy` are two methods on purpose. Warren's second most common failure across 44
failed runs was **dropped commit** — the agent did the work and the container died with it.

### 3.3 `WorkSource` — where work comes from

```java
public interface WorkSource {
    WorkSourceType type();
    WorkSourceCapabilities capabilities();   // supportsLabels, supportsTransitions, supportsPlans
    List<WorkItemRef> candidates(WorkQuery q);
    WorkItem fetch(WorkItemRef ref);
    void comment(WorkItemRef ref, String body);
    void transition(WorkItemRef ref, String state);
    Set<String> labels(WorkItemRef ref);
}
```

Arms: GitHub Issues, GitLab Issues, Jira — reusing the HTTP clients the `spire-context-*` modules
already have. Same hosts, same registry, wider rights (see [PACKAGING.md](./PACKAGING.md) §Knowledge
vs Build).

## 4. Identity — derive, never register

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

Topics after the change: `cs.integration`, `cs.commands`, `cs.events`, `cs.results`, **`cs.run-events`**,
`cs.dlq`. All keyed by their aggregate id.

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
| `workspace_lease` | which sandbox owns which workspace, for the orphan watchdog |
| `harness_credential_state` | pool member health: available / rate-limited-until / rejected / disabled |

**Changed**

`llm_charge` gains `capability` and `credential_ref`. Cheap now, **impossible to backfill** — the
same lesson as `review_finding` shipping with no backfill.

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
3. **The image.** Selected from an operator allowlist. A repository may not name one (ADR-035).
4. **The label.** Untrusted *control*, not untrusted data — fencing does not help. Bounded by an
   operator ceiling and an actor allowlist (ADR-032).

The Docker arm's socket requirement is root-equivalent on the host and is documented as such rather
than mitigated away. The Kubernetes arm removes it.

## 10. What the build enforces

`spire-arch` today fails the build when a core module names an SCM or context provider outside a
reasoned allowlist. It is extended to **harness, runtime and work-source** names on the same terms,
**in the same commit as the first seam** — not after the first leak. The SCM version of this check
found three real leaks, one of which was a live defect returning 500 instead of 404.
