# Factory M4, slice 1 — verify

**Status:** design, 2026-10-07. Agreed with the operator in conversation, one decision at a time (§9).
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

**Not in this slice.** Automatic retries and the plan coordinator (a later M4 slice; the operator asked
for automated coordinator execution there). A repository file that declares the checks (option H1, §2).
Generated specifications and multi-step plans (FR-F18/F19), step summaries (FR-F31), the licence
provenance report, network limits for verify, and live GitLab/Bitbucket proof.

## 2. Where the checks are declared — the operator's build setup

The operator types the check commands in the repository's build setup, next to the M3.5 build defaults.
One command per line, run in order. A second field holds the verify time limit for all commands together,
default 1800 seconds.

**Why not a repository file.** A verify command is *control*: code reads it and obeys it. ADR-036 decided
that repository content may only narrow behaviour, and that a repository-facing selection comes from an
operator allowlist, never from free text. A file read from the agent's own branch is worse: the agent
writes that workspace and could set the check to `true`, which forges the green FR-F20 forbids.

Prior art (retrieved 2026-10-07) agrees. Every surviving system keeps the declaration out of the
untrusted ref or behind an admin allowlist:
- **Out of the untrusted ref.** Copilot coding agent's `copilot-setup-steps.yml` runs only from the
  default branch.
- **Behind an admin allowlist.** Renovate's `postUpgradeTasks` must match the global-only
  `allowedCommands`, and it reads the default branch only.
- **Operator settings.** Codex cloud, Devin and Jules keep setup and check commands in operator
  settings; the repository gives hints at most.
- **The exception.** Sweep read free-text commands from the repository, and it is discontinued.

**Later: H1, the repository proposes and the operator pins.** A repository file read from the target
branch is shown in build setup. The operator approves it, and the approved copy and its content hash bind
into the preparation. When the file changes, verify reports `unverified: declaration changed`. This keeps
team ownership without breaking ADR-036. It is not in this slice.

**Copy and bind.** Preparation copies the commands and the time limit into the preparation, as it does
the build defaults (ADR-045). A setup change after preparation does not affect a prepared item.

## 3. Where the checks run — a clean copy of the checkpoint

The kept build workspace holds the commits, and it can also hold loose files the agent never committed.
Only the commits are delivered. So verify does not run in that workspace. A trusted init container
builds a **new** workspace that contains exactly the checkpoint commit, and the commands run there.

This gives one guarantee: **the commit that passed is the commit that gets pushed.** Running git to clean
the agent's own workspace would execute agent-controlled `.git/config` and hooks (the ADR-039 attack).
A fresh repository has neither.

### 3.1 The verify unit

1. **Init** (publisher image, new mode `spire-verify-prepare`):
   - clones the base, as build init does;
   - fetches the agent's bundle from the kept run's handoff volume, mounted read-only;
   - checks out the recorded checkpoint commit, detached, into a new volume.

   It fails, giving unverified `checkpoint_missing`, when the handoff volume is gone or the commit is
   absent from the bundle. Git addresses objects by hash, so the tree is exactly that commit.
2. **Checks** (agent image, which carries the repository's toolchain), one container per command, in
   order:
   - argv `["/bin/sh", "-c", <command>]`, working directory the new volume;
   - no handoff mount, no model credential, no run secrets;
   - the agent's CPU and memory limits.

   The first non-zero exit stops the sequence.
3. **Collection.** The run worker keeps the last 200 lines of each container's log stream, emits the
   result, and deletes the verify volume. The kept build unit is not changed.

The runtime gains `PublicationRuntime.verifyHeld(...)`. Like `publishHeld`, it requires the kept unit's
agent and publisher containers to have exited and its hold labels to match. Verify containers carry the
run id and the verify attempt id as labels.

### 3.2 Outcomes

| Case | Outcome | Reason |
|---|---|---|
| every command exited 0 | `PASSED` | — |
| a command exited non-zero other than 127 | `FAILED` | `check_failed` |
| no commands in the preparation | `UNVERIFIED` | `no_checks_declared` |
| a command exited 127 (the image lacks the tool) | `UNVERIFIED` | `tool_missing` |
| the time limit was reached; the container is killed | `UNVERIFIED` | `timed_out` |
| the bundle or checkpoint could not be read | `UNVERIFIED` | `checkpoint_missing` |
| a container or volume could not be created, or the worker died mid-verify | `UNVERIFIED` | `verify_could_not_run` |

A timeout is unverified, not failed: it says nothing about whether the code is wrong. An empty
command list is not refused at preparation. It produces a visible unverified result, because zero
runnable checks must never give the same green as zero failing ones (AUTONOMY.md).

### 3.3 Network

Verify containers get the agent's network. The Docker arm enforces no egress policy
(`DockerRunRuntime`), so this is recorded as a limit in `UNVERIFIED.md`, as it is for builds today.

## 4. Contract and data

### 4.1 Contract (`spire-contract`)

```java
public record WorkVerification(UUID attemptId, String head, Outcome outcome, String reason,
                               List<CheckResult> checks) {
    public enum Outcome { PASSED, FAILED, UNVERIFIED }
}
public record CheckResult(String command, Integer exitCode, long wallMillis, boolean outputKept) {}
```

- `WorkExecution.verificationAttempt` (a bare `UUID`) is replaced by `WorkVerification verification`, and
  `verified(UUID)` by `verified(WorkVerification)`. A wither enumerates the components once, next to the
  record (the CLAUDE.md gotcha on wire records).
- The output tails are not in the event. They are stored encrypted (§4.3) and keyed by attempt.
- New command `VerifyWork(runId, workItemId, generation, attemptId, head, commands, timeoutSeconds)`.
- New integration event `RunWorkVerified(runId, attemptId, WorkVerification)`.
- Both are keyed by run id. `ContractSchemaSnapshotTest` is extended to the nested types by hand, because
  it does not recurse.

### 4.2 Lifecycle

Today `PhaseResult.successful=false` yields `failed / phase_failed`, which is final for the item. FR-F20
says verify "fails a step, never a work item". So verify gets its own result path:

| Verify result | Item |
|---|---|
| `PASSED` with `head` = the checkpoint head | `PHASE_COMPLETED`, then deliver |
| `FAILED` | gate opened on verify, `waiting_approval / verify_failed` |
| `UNVERIFIED` | gate opened on verify, `waiting_approval / verify_unverified` |
| any `head` other than the checkpoint head | refused, `phase_evidence_mismatch` (existing check) |

The gate opens in every verify mode except `off`, `auto` included: only a human accepts a result that is
not passed. It reuses `ResolveGate.approve`:
- `true` — **Retry build.** The item returns to the build phase with a new attempt. The run counts against
  `maxRunsPerItem`, so the existing cap still ends a loop.
- `false` — **Stop.** The item stops with `verify_stopped_by_operator`.

All channels that resolve gates today (dashboard, bound tracker command, native PR review where supported)
resolve this one through the same `ResolveGate`.

Delivery requires `verification.outcome == PASSED`, in addition to today's checks in `WorkDelivery`:
- the verification matches the run and preparation;
- a completed `work_phase_attempt` for verify exists in the same generation;
- `factory_run.checkpoint_head` equals the verified head.

`WorkPhaseCapability` reports verify as available when the item has a preparation and the run transport is
available. An empty command list is still "available": it runs and yields `no_checks_declared`.

### 4.3 Storage (orchestrator)

- **`repository_build_defaults`:** new columns `verify_commands text` and `verify_timeout_seconds int`.
  `BuildDefaults`, its input record, `RepositoryBuildResource` and the preparation copy carry them.
- **`work_verify_effect`:** one row per verify attempt, like `work_run_effect` for builds. It holds the
  attempt id, run id, generation, head, outcome, reason, the checks, and the output tails Tink-encrypted.
  Tails can quote source, so they fall under the existing encryption boundary.
- One migration adds both: `V87__verify.sql` (V86 is the highest on master today; take the next free number at implementation).

### 4.4 Retry build

The retried build starts from the **last checkpoint commit**, not the base, so the agent corrects its own
work. Build init gains an optional input, "start from the checkpoint of run X". It uses the same bundle
import as `spire-verify-prepare`. The composed prompt gains one section: the failed command or the
unverified reason, and the stored output tail. The previous kept unit is deleted once the new build
checkpoints.

## 5. Screens

- **Build setup.** A "Check commands" text area (one per line) and "Verify time limit".
  - When the area is empty: "No checks: builds will stop as unverified."
  - The preparation panel shows the copied commands.
- **Work item, verify step.** Three looks that cannot be confused:
  - passed: green tick;
  - failed: red cross;
  - unverified: amber "not checked" icon, never a tick.

  Below it is a table of commands with exit code and time. Each row opens to its output tail.
- **Gate panel.** The failed command or the unverified reason, then **Retry build** and **Stop**.
- **Reasons.** `workReasons.ts` gets texts for:
  - `verify_failed`, `verify_unverified`, `verify_stopped_by_operator`;
  - each sub-reason of §3.2.
- **Status plumbing, changed together.** These must all change in one go (the CLAUDE.md gotcha: a
  missed one renders as success):
  - the `api.ts` status union;
  - the labels in `WorkItems.tsx`;
  - the `workJourney.ts` mapping;
  - the Needs-you filter.

  Icons come from lucide-react.

## 6. Failure and recovery

| Event | Handling |
|---|---|
| Run worker dies during verify | On start, it finds verify containers by label, kills them, deletes the volume, and emits `UNVERIFIED / verify_could_not_run`. It never infers passed. |
| Duplicate or late `RunWorkVerified` | One result per attempt id. A second is ignored and logged. |
| Head mismatch | Refused, `phase_evidence_mismatch`. |
| Kept unit removed by hand | Init cannot read the bundle, so `UNVERIFIED / checkpoint_missing`. Retry build is still offered. |
| Setup changed after preparation | No effect. The item uses its copy. |
| Takeover or retirement during verify | Verify containers are stopped with the item's other compute. The result of a superseded attempt is refused by attempt id. |

## 7. Testing

CI runs every test. Nothing runs on the host.

- **Contract.** The lifecycle decide table for each outcome. Failed and unverified open a gate and never
  `phase_failed`. Approve returns to build; reject stops. `maxRunsPerItem` still ends retries.
- **Orchestrator.**
  - Delivery refuses `FAILED`, `UNVERIFIED` and a wrong head.
  - `WorkDeliveryIT` and `WorkReviewIT` take a real `RunWorkVerified` result. The TEST-only verify
    override is deleted.
  - Encryption round trip for the output tails.
- **Run worker (real Docker):**
  - one case per row of §3.2;
  - a stray uncommitted file that would make a check pass still fails in verify, which proves the clean
    copy;
  - the verify container's environment holds no model credential;
  - worker death mid-verify yields `verify_could_not_run`.
- **Agent image contract.** `spire-agent-image verify` checks for `/bin/sh`. Toolchain presence stays
  declared-only. A missing tool surfaces as `tool_missing`.
- **UI.**
  - An unverified result renders no tick.
  - Every new status has a label and a journey mapping.
  - The gate offers exactly Retry build and Stop.
- **Mutation.** Each guard is broken on its production line, and exactly one test must fail.

## 8. Documentation changes

- **PRD FR-F20:** "repository-owned checks, declared by the operator in M4; a repository-declared file is
  a later option (H1)".
- **New ADR-046:** where checks are declared, where they run, the three outcomes, and the verify gate.
- **ADR-033 and the `WorkPolicy.Phase` order.** Both list verify → review → deliver, but the code and
  ADR-045 run verify → deliver → review. ADR-033 is corrected to match the code.
- `UNVERIFIED.md`: the verify network limit, and the new claims until the live proof.
- `AGENT-IMAGE-CONTRACT.md`: the `/bin/sh` rule.
- `RUN-TOPOLOGY.md`: the verify unit.
- At acceptance: `M4-VERIFY-ACCEPTANCE.md`, the HISTORY entry, and the CLAUDE.md status.

## 9. Decisions taken with the operator (2026-10-07)

| # | Question | Decision |
|---|---|---|
| 1 | Where are the check commands declared? | **A**, the operator's build setup. H1 (repository proposes, operator pins) later. Chosen after a researched comparison of A, B and two hybrids. |
| 2 | Where do the checks run? | **A clean copy of the checkpoint commit**, in a verify container without the model credential. Not in the kept agent workspace. |
| 3 | What happens on failure? | **Stop at a gate**: Retry build or Stop. Automatic retries belong to the plan coordinator in a later M4 slice, which the operator requires. |
