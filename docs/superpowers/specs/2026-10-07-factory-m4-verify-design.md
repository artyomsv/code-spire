# Factory M4, slice 1 — verify

**Status:** design, 2026-10-07, revised the same day after an adversarial review against the code (§10
lists what changed). Agreed with the operator in conversation, one decision at a time (§9).
**Parent:** [factory ROADMAP, M4](../../factory/ROADMAP.md#m4--specification-plan-and-verify), FR-F20 in
the [factory PRD](../../factory/PRD.md). Follows [M3.5](2026-09-16-factory-m35-one-ticket-to-a-build-design.md),
accepted 2026-10-07 in [M35-ACCEPTANCE](../../factory/M35-ACCEPTANCE.md), whose three live builds all
stopped at `verify / capability_unavailable`.

## 1. Goal and exit

A held item build is checked by the repository's own commands. The result is one of three outcomes:
**passed**, **failed** or **unverified**. Only passed lets the build continue to delivery. Failed and
unverified stop the item at a gate where a human picks "Retry build" or "Stop". Unverified never renders
as passed.

**Exit criterion.** On `spire-test`, with check commands in the repository's build setup:

| Ticket | Setup | Must show |
|---|---|---|
| 1 | checks that pass | verify **passed**, then deliver opens the first live item-linked pull request, and the reviewer reviews it |
| 2 | a check that fails on the first build | a verify gate with the failed command and its output tail; **Retry build** starts a new run from the checkpoint with the failure in its prompt; the retry passes |
| 3 | a command whose tool is not in the agent image | **unverified, "tool missing"**, a gate, and no tick anywhere on screen |

The record goes in `docs/factory/M4-VERIFY-ACCEPTANCE.md`.

**Not in this slice.**
- Automatic retries and the plan coordinator. They come in a later M4 slice; the operator asked for
  automated coordinator execution there.
- A repository file that declares the checks (option H1, §2).
- Generated specifications and multi-step plans (FR-F18/F19), and step summaries (FR-F31).
- The licence provenance report, and network limits for verify.
- Live GitLab/Bitbucket proof.

**Precondition, unchanged.** `WorkItemLifecycle.enter` refuses on unknown usage before any phase
(`run_usage_unknown`). A build whose usage is unknown never reaches verify.

## 2. Where the checks are declared — the operator's build setup

The operator types the check commands in the repository's build setup, next to the M3.5 build defaults.
One command per line, run in order. A second field holds the verify time limit for all commands together.
It defaults to 1800 seconds and is bounded by the run worker's maximum (§3.4).

**Why not a repository file.** A verify command is *control*: code reads it and obeys it. ADR-036 decided
that repository content may only narrow behaviour, and that a repository-facing selection comes from an
operator allowlist, never from free text. A file read from the agent's own branch is worse: the agent
writes that workspace and could set the check to `true`, which forges the green FR-F20 forbids.

Prior art (retrieved 2026-10-07) agrees. Every surviving system keeps the declaration out of the untrusted
ref or behind an admin allowlist:
- **Out of the untrusted ref.** Copilot coding agent's `copilot-setup-steps.yml` runs only from the
  default branch.
- **Behind an admin allowlist.** Renovate's `postUpgradeTasks` must match the global-only
  `allowedCommands`, and Renovate reads the default branch only.
- **Operator settings.** Codex cloud, Devin and Jules keep setup and check commands in operator settings;
  the repository gives hints at most.
- **The exception.** Sweep read free-text commands from the repository, and it is discontinued.

**Later: H1, the repository proposes and the operator pins.** A repository file read from the target
branch is shown in build setup. The operator approves it, and the approved copy and its content hash bind
into the preparation. When the file changes, verify reports `unverified: declaration changed`. This keeps
team ownership without breaking ADR-036. It is not in this slice.

**Copy and bind.** Preparation copies the commands and the time limit into the preparation, as M3.5 does
for `payWith`. A new `WorkPreparation.VERIFY_BINDING = 5` adds both to the hashed binding, so the gate
that approves a preparation approves the exact commands. Preparations with an older binding version have
no commands and verify as `no_checks_declared`. A setup change after preparation does not affect a
prepared item.

## 3. Where the checks run — a clean copy of the checkpoint

The kept build workspace holds the commits, and it can also hold loose files the agent never committed.
Only the commits are delivered. So verify does not run in that workspace. A trusted init container builds
a **new** workspace that contains exactly the checkpoint commit, and the commands run there.

This gives one guarantee: **the commit that passed is the commit that gets pushed.** Running git to clean
the agent's own workspace would execute agent-controlled `.git/config` and hooks (the ADR-039 attack).
A fresh repository has neither.

### 3.1 The verify unit

A verify unit is its own spec record, `VerifyUnitSpec`. `RunUnitSpec` requires the three fixed build roles
and does not fit.

1. **Init** (publisher image, new mode `spire-verify-prepare`):
   - clones the base, as build init does;
   - removes the remote before it reads any agent-written data (as `WorkspaceClone` does today);
   - reads the agent's bundles from the kept run's handoff volume, mounted read-only, through the same
     copy-out-of-reach, no-follow path as `PublishRepo.fetchBundle`;
   - fetches them newest first, until the recorded checkpoint head resolves. Each bundle is `BASE..HEAD`
     at its moment, and the gated checkpoint can be absent from the newest one if the agent rewrote
     history;
   - checks out that commit, detached, into a new volume, and writes the resolved head to its output.

   Git addresses objects by hash, so the tree is exactly that commit. Nothing executes in init: no hooks,
   no agent config, no filters. A tree whose attributes need a filter driver (for example `filter=lfs`)
   fails the checkout and yields `verify_could_not_run`.
2. **Checks** (agent image, which carries the repository's toolchain), one container per command, in
   order:
   - argv `["/bin/sh", "-c", <command>]`, working directory the new volume;
   - no handoff mount, no model credential, no run secrets;
   - the agent's CPU and memory limits.

   The first non-zero exit stops the sequence. After the first check container runs, no trusted process
   runs git in the verify volume again: the verified head is the one init reported.
3. **Collection.** The run worker reads each container's log with a tail of 200 lines and caps the bytes
   per line, so an agent-controlled log cannot force an unbounded allocation. It emits the result and
   deletes the verify volume. The kept build unit is not changed.

**Ownership.** Init (publisher image) writes the verify volume and the check containers (agent image) use
it. Both run as uid 1001. That becomes an explicit clause of the agent image contract.

**Runtime.** `PublicationRuntime.verifyHeld(...)` creates the unit against a kept build unit. Like
`publishHeld`, it requires the kept unit's agent and publisher containers to have exited and its hold
labels to match. Verify containers and the verify volume carry:
- the run id;
- `PUBLICATION_HOLD_LABEL`, equal to the binding, so `requireHeld` accepts them;
- a role label `verify` and the verify attempt id.

`discoverUnits` filters on role, so a verify container never becomes the unit handle. Verify resources are
removed before delivery starts.

### 3.2 Outcomes

| Case | Outcome | Reason |
|---|---|---|
| every command exited 0 | `PASSED` | — |
| a command exited non-zero, other than 126 or 127 | `FAILED` | `check_failed` |
| no commands in the preparation | `UNVERIFIED` | `no_checks_declared` |
| a command exited 126 or 127 (not executable, or not found) | `UNVERIFIED` | `tool_missing` |
| the time limit was reached; the container is killed | `UNVERIFIED` | `timed_out` |
| the checkpoint head did not resolve from the bundles | `UNVERIFIED` | `checkpoint_missing` |
| a container or volume could not be created or started, or the checkout failed, or the worker died mid-verify | `UNVERIFIED` | `verify_could_not_run` |

**`tool_missing` is inferred.** A shell returns 126 and 127 for a command it cannot run. A test suite can
also exit 127 on purpose. The UI says "a tool was probably missing" and shows the exit code and the
output tail.

**A timeout is unverified, not failed.** It says nothing about whether the code is wrong. The limit
covers init and all check containers together.

**An empty command list is not refused at preparation.** It produces a visible unverified result,
because zero runnable checks must never give the same green as zero failing ones (AUTONOMY.md).

### 3.3 Network

Verify containers get the agent's network. The Docker arm enforces no egress policy
(`DockerRunRuntime`), so this is recorded as a limit in `UNVERIFIED.md`, as it is for builds today.

### 3.4 Time, slots and the ack budget

- **Ack budget.** `RunAckBudget` sizes the consumer's ack window from `spire.run.max-wall-clock-seconds`.
  The verify time limit covers init and the checks together (init waits at most the time left), so it
  must not exceed that maximum. Build setup refuses a larger value on save (against the orchestrator's
  `spire.factory.wall-clock-seconds`), and the run worker refuses it again against its own maximum, as
  `UNVERIFIED / verify_could_not_run`.
- **Slots.** A verify unit takes the run worker's one execution slot for its length, like a build. It is
  claimed under its own key, `verify:<attemptId>` (the claim slot beside the build's run id), never under the build's `execute` slot, which is already
  claimed.
- **Item caps.** Verify wall seconds count toward `maxWallClockSeconds`, so a long verify shortens the
  wall clock left for a retry.

## 4. Contract and data

### 4.1 Contract (`spire-contract`)

```java
public record WorkVerification(UUID attemptId, String head, Outcome outcome, String reason,
                               List<CheckResult> checks) {
    public enum Outcome { PASSED, FAILED, UNVERIFIED }
}
public record CheckResult(String command, Integer exitCode, long wallMillis, String outputTail) {}
```

- **`WorkExecution`.** It gains a **new nullable component**, `verification`, through a wither that
  enumerates the components once, next to the record.
  - `verificationAttempt` stays. Stored `WorkItemEvent`s carry `"verificationAttempt"`, the ObjectMapper
    has no configured leniency, and renaming or retyping a stored component is the break
    `ContractSchemaSnapshotTest` names.
  - `verified(...)` sets both components.
  - The golden file is updated on purpose.
- **Messages.**
  - New command: `VerifyWork(runId, workItemId, generation, attemptId, head, commands, timeoutSeconds)`.
  - New integration event: `RunWorkVerified(runId, attemptId, WorkVerification)`.
  - Both are keyed by run id, as factory run messages are today (`RunCommand`, `RunResultSerializer`).
- **Topic for the result.** `RunWorkVerified` travels on a **new topic**, `cs.run-verifications`, not
  on `cs.run-results`. `WorkItemRunBridge.record` would otherwise store it as the build's terminal
  payload under `COALESCE`, and the later publication `RunFinished` would be dropped.
- **Output tails.** The tails ride the event, bounded to 200 lines and a byte cap per check. This follows
  the precedent of `RunEventRecord.text` on `cs.run-events` (ADR-014: short retention plus broker disk
  encryption). The orchestrator encrypts them at rest (§4.3).
- **Schema snapshot.** `ContractSchemaSnapshotTest` does not recurse, so `WorkVerification` and
  `CheckResult` are added to its hand-listed nested types.

### 4.2 Lifecycle

Today `PhaseResult.successful=false` yields `failed / phase_failed`, which is final for the item. FR-F20
says verify "fails a step, never a work item". So verify gets its own result path in the decider:

| Verify result | Item |
|---|---|
| `PASSED`, `head` = the checkpoint head | `PHASE_COMPLETED`, then deliver |
| `FAILED` | `GATE_OPENED` on verify, `waiting_approval / verify_failed` |
| `UNVERIFIED` | `GATE_OPENED` on verify, `waiting_approval / verify_unverified` |
| any `head` other than the checkpoint head | refused, `phase_evidence_mismatch` (existing check) |

**Result gate.** Today `enter` opens a gate only for `approve && !approved`. A new decider branch opens a
**result gate**, keyed on the verify outcome, in every verify mode except `off`, `auto` included. Only a
human accepts a result that is not passed. AUTONOMY.md says `auto` proceeds; ADR-046 records this one
exception: `auto` proceeds on passed only.

**`approve` mode.** It keeps today's pre-verify gate, where the human approves running the checks. A
result gate follows only when the result is not passed. Passed proceeds without a second gate.

**Answers.** The result gate reuses `ResolveGate.approve`. `WorkItemTransitions.answer` gains one branch
for a gate whose reason is `verify_failed` or `verify_unverified`:
- **`true` — Retry build.** The phase rewinds to `build`, and the item enters it with `approved=true`.
  The answer therefore also stands in for a `build: approve` gate: the human just chose to build again.
  `WorkProgress.start("build")` adds one to `runs` and `steps`, so `maxRunsPerItem` and
  `maxStepsPerPlan` still end a loop.
- **`false` — Stop.** The item stops with `verify_stopped_by_operator`, not the generic `gate_rejected`.

**Channels.** The dashboard and a bound tracker command can answer the gate. A native PR review cannot:
it answers only `land` gates, and no pull request exists before deliver.

**Delivery** requires `verification.outcome == PASSED`, in addition to today's checks in `WorkDelivery`:
- the verification matches the run and preparation;
- a completed `work_phase_attempt` for verify exists in the same generation;
- `factory_run.checkpoint_head` equals the verified head.

**Capability.** `WorkPhaseCapability` reports verify as available when the item has a preparation and the
run transport is available. An empty command list is still "available": it runs and yields
`no_checks_declared`.

### 4.3 Storage (orchestrator)

- **`repository_build_defaults`:** new columns `verify_commands text` and `verify_timeout_seconds int`.
  `BuildDefaults`, its input record, `RepositoryBuildResource` and the preparation copy carry them. Save
  enforces the bound of §3.4.
- **`work_verify_effect`:** one row per verify attempt, like `work_run_effect` for builds. It holds:
  - the attempt id, the run id of the build it verified, the generation and the head;
  - the outcome, the reason and the checks;
  - the output tails, Tink-encrypted, because they can quote source.

  The hold outbox reads this table as well as `work_run_effect`, so a takeover reaches verify units (§6).
- **Migration:** one migration adds both, `V87__verify.sql`. V86 is the highest on master today; take the
  next free number at implementation.

### 4.4 Retry build

The retried build starts from the **last checkpoint commit**, not the base, so the agent corrects its own
work.
- **Where the source run is kept.** `WorkProgress.start("build")` clears `execution`, and the next claim
  overwrites `WorkControl.runId`. So the source run id and head are read from the latest
  `work_verify_effect` row for the item, not from progress.
- **Assembly.** `WorkRunAssembly` passes them to build init as "start from the checkpoint of run X". Build
  init uses the same bundle import as `spire-verify-prepare`.
- **Prompt.** The composed prompt gains one section: the failed command or the unverified reason, and the
  stored output tail.
- **Cleanup.** The previous kept unit is deleted once the new build checkpoints.

## 5. Screens

- **Build setup.**
  - A "Check commands" text area (one per line) and "Verify time limit", showing its maximum.
  - When the area is empty: "No checks: builds will stop as unverified."
  - The preparation panel shows the copied commands.
- **Work item, verify step.** Three looks that cannot be confused:
  - passed: green tick;
  - failed: red cross;
  - unverified: amber "not checked" icon, never a tick.

  Below it is a table of commands with exit code and time; each row opens to its output tail.
  `WorkItemSteps.tsx` today renders "Verification recorded" for any recorded attempt. It changes to read
  the outcome.
- **Gate panel.** The failed command or the unverified reason, then **Retry build** and **Stop**.
- **Reasons.** `workReasons.ts` gets texts for:
  - `verify_failed`, `verify_unverified`, `verify_stopped_by_operator`;
  - each sub-reason of §3.2.
- **Statuses.** No new workflow status is introduced: the outcomes are reasons under `waiting_approval`
  and `stopped`. The Needs-you filter in `workJourney.ts` must include the two new `waiting_approval`
  reasons.
- **Icons** come from lucide-react.

## 6. Failure and recovery

| Event | Handling |
|---|---|
| Run worker dies during verify | On start, it finds verify containers by role label, kills them, deletes the volume, and emits `UNVERIFIED / verify_could_not_run`. It never infers passed. |
| Duplicate or late `RunWorkVerified` | One result per attempt id. A second is ignored and logged. |
| Head mismatch | Refused, `phase_evidence_mismatch`. |
| Kept unit removed by hand | Init cannot read the bundles, so `UNVERIFIED / checkpoint_missing`. Retry build is still offered; it starts from the base because no checkpoint is readable, and the prompt says so. |
| Setup changed after preparation | No effect. The item uses its copy. |
| Takeover or retirement during verify | `DockerRunRuntime.cancel` gains the `verify` role (today it kills only agent, publisher and delivery-publisher). The hold outbox is fed from `work_verify_effect`. The result of a superseded attempt is refused by attempt id. |

## 7. Testing

CI runs every test. Nothing runs on the host.

- **Contract.**
  - The decider table for each outcome: failed and unverified open a result gate and never
    `phase_failed`.
  - `auto` opens a result gate on failed and unverified. `approve` opens the pre-verify gate, then a
    result gate only when the result is not passed.
  - The binding hash changes when a command changes (`VERIFY_BINDING`).
  - The schema snapshot holds an old `WorkExecution` without `verification`.
- **Orchestrator.**
  - Retry build rewinds to build, starts a new attempt, and adds one run. `maxRunsPerItem` ends the loop.
  - Stop gives `verify_stopped_by_operator`.
  - A PR-review answer to a verify gate is refused.
  - Delivery refuses `FAILED`, `UNVERIFIED` and a wrong head.
  - `WorkDeliveryIT` and `WorkReviewIT` take a real `RunWorkVerified` result. The TEST-only verify
    override is deleted.
  - A `RunWorkVerified` never reaches `WorkItemRunBridge`.
  - The tails survive an encryption round trip.
  - Build setup refuses a time limit above the bound.
- **Run worker (real Docker).**
  - One case per row of §3.2: exit 0, exit 1, exit 127, time limit, no commands, a missing checkpoint,
    a failed checkout.
  - A stray uncommitted file that would make a check pass still fails in verify. This proves the clean
    copy.
  - The verify container's environment holds no model credential.
  - A checkpoint absent from the newest bundle still resolves from an older one.
  - Verify resources pass `requireHeld`, and `discoverUnits` returns the agent, not a verify container.
  - `cancel` stops a running verify container.
  - Worker death mid-verify gives `verify_could_not_run`. This case uses the existing killed-JVM
    harness of slice 8b.
- **Agent image contract.** `spire-agent-image verify` checks for `/bin/sh` and uid 1001. This check is
  Mode S in `UNVERIFIED.md` until it runs against a real image. Toolchain presence stays declared-only;
  a missing tool surfaces as `tool_missing`.
- **UI.**
  - `UNVERIFIED` renders no tick in the step, the list or the gate. This is a discriminating test: it
    fails if the outcome is ignored.
  - The new reasons have texts.
  - The Needs-you filter includes them.
  - The gate offers exactly Retry build and Stop.
- **Mutation.** Each guard is broken on its production line, and exactly one test must fail.

## 8. Documentation changes

- **PRD FR-F20:** "repository-owned checks, declared by the operator in M4; a repository-declared file is
  a later option (H1)".
- **New ADR-046:**
  - where checks are declared, and where they run;
  - the three outcomes;
  - the result gate, and its exception to `auto`.
- **ADR-033 and the `WorkPolicy.Phase` order.** Both list verify → review → deliver, but the code runs
  verify → deliver → review. ADR-033 is corrected to match the code.
- **`UNVERIFIED.md`:** the verify network limit, the `/bin/sh` image check, and the new claims until the
  live proof.
- **`AGENT-IMAGE-CONTRACT.md`:** the `/bin/sh` and uid 1001 clauses.
- **`RUN-TOPOLOGY.md`:** the verify unit.
- **At acceptance:** `M4-VERIFY-ACCEPTANCE.md`, the HISTORY entry, and the CLAUDE.md status.

## 9. Decisions taken with the operator (2026-10-07)

| # | Question | Decision |
|---|---|---|
| 1 | Where are the check commands declared? | **A**, the operator's build setup. H1 (repository proposes, operator pins) later. Chosen after a researched comparison of A, B and two hybrids. |
| 2 | Where do the checks run? | **A clean copy of the checkpoint commit**, in a verify container without the model credential. Not in the kept agent workspace. |
| 3 | What happens on failure? | **Stop at a gate**: Retry build or Stop. Automatic retries belong to the plan coordinator in a later M4 slice, which the operator requires. |

## 10. What the review changed (2026-10-07)

An adversarial review read the first draft against the code. Its findings are folded in above:

| # | Finding | Fix |
|---|---|---|
| 1 | Approving a gate re-enters the gate's own phase, so "Retry build" would have re-run verify. The source run was also lost, because `start("build")` clears the execution. | §4.2: rewind to build in `answer`. §4.4: read the source run from `work_verify_effect`. |
| 2 | Output tails had no path from the run worker to the orchestrator. | §4.1: tails ride the event. |
| 3 | A verify result on `cs.run-results` would be taken as the build's terminal payload. | §4.1: new topic. §3.4: own claim key. |
| 4–5 | Verify resources failed `requireHeld` and could become the unit handle. `cancel` did not reach them. | §3.1 labels and role filter; §6 cancel and outbox. |
| 6 | Retyping `verificationAttempt` breaks stored events. | §4.1: new nullable component. |
| 7 | The commands were copied but not bound. | §2: `VERIFY_BINDING = 5`. |
| 8 | No decider path opened a gate in `auto` mode. Reject gave `gate_rejected`. The `approve` mode had an unclear double gate. | §4.2. |
| 9 | An unbounded verify time limit broke the ack budget and held the slot. | §3.4. |
| 10–18 | Cap accounting, the PR-review channel, the exit code 126, multiple bundles, init credential handling, LFS filters, bounded log tails, unit spec and uid, UI status plumbing, and test gaps. | §§2–7. |
