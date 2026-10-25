# Factory M4 slice 3 — specification, plan and steps: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan slice by slice. Steps use checkbox (`- [ ]`) syntax.

**Goal:** a repository can choose generated preparation. The factory then writes a specification with a
read-only harness run, posts it to the ticket, gets it approved, writes a plan of N vertical-slice steps the same
way, and builds the plan step by step on one branch: each step verifies, an empty step completes as empty, each
step's summary is stored and carried into every later step's prompt, and the last non-empty step's head is
delivered as one pull request. Repositories that keep the default (`composed`) see no change.

**Spec:** [`docs/superpowers/specs/2026-10-25-factory-m4-spec-plan-steps-design.md`](../specs/2026-10-25-factory-m4-spec-plan-steps-design.md).
Section 9 (review amendments, "A1".."A22" below) overrides the sections it names. §n refers to the spec.
The land slice (ADR-048, [`2026-10-21-factory-m4-land-design.md`](../specs/2026-10-21-factory-m4-land-design.md),
plan [`2026-10-22-factory-m4-land.md`](2026-10-22-factory-m4-land.md)) is the pattern for an `EntryMode`-style
gate skip, `startKeepingExecution`, encrypted per-effect payloads, durable effect sweeps and result gates.

**Tech stack:** Java 25, Quarkus 3.38.3, PostgreSQL/Flyway, JGit, POSIX sh (agent entrypoint), React + vitest.

**Draft PR:** [#287](https://github.com/artyomsv/code-spire/pull/287), `feat/m4-spec-plan` → `master`.

## Decisions this plan makes (spec §10 "left to the builder")

Each is the author's call; a reviewer may overturn any of them before its slice starts.

1. **Dark until wired.** The orchestrator reads `spire.work.generated-preparation` (default `false`). While it
   is false, `GET /api/repositories/{repository}/factory/build/options` does not offer `generated`, `PUT` refuses it with
   `preparation_unavailable`, and admission ignores a stored `generated`. Service tests that exercise the
   generated path enable it through a `QuarkusTestProfile` (`GeneratedPreparationProfile`). Slice 11 deletes the
   flag, so the shipped behaviour is governed by the repository setting alone (default `composed`).
2. **The item records its preparation mode at admission (A16 over A5).** A5 asks the copy sweep to require the
   repository's `preparation = 'composed'`; A16 says a switch applies only to items admitted after it. Filtering
   on the repository column alone breaks A16 both ways: an item admitted under `composed` would stop being copied
   after a switch to `generated`, and one admitted under `generated` would be copied after a switch back. So the
   admission decision records `WorkDraft` on the item when the repository says `generated`; the copy sweep
   requires `work_item.preparation_mode = 'composed'` (a projection column, default `composed`), which implies
   the repository was `composed` when the item was admitted. The sweep's class comment says so (A5).
3. **Distinct wire commands.** A spec or plan run is `RunCommand.ExecuteWorkDraftRun`, a new discriminator, for the
   reason `ExecuteWorkRun` has its own: an older run worker must refuse it, never run it as a pushing `ExecuteRun`.
   Its result is `RunResult.RunWorkArtifact`.
4. **The READ_ONLY publisher gets no write credential at all**, and does not clone. It waits for `DONE`, counts
   bundles only to report that the repository was changed (a warning), and reads the two handoff files. That is
   stronger than A1's "the publisher mode is the guard": with no secret in its environment it cannot push even if
   its code were wrong. The init container still gets the read credential it needs to clone.
5. **The repository's `.codespire` rules** are not fetched by the orchestrator: the spec and plan prompts tell
   the agent to read `.codespire` at the repository root if it exists, since the run has the clone.
6. **A failed spec or plan run** (RunFailed) waits at `spec|plan / awaiting_input / draft_run_failed`; Readmit
   (which keeps nothing of an unapproved draft) and Stop are the ways on. It is not `failed`: FR-F20.
7. **A step whose checkpoint is missing (A2)** becomes an UNVERIFIED verify result with the existing reason
   `checkpoint_missing` on the previous step's head, so today's verify gate (Retry / Stop) opens unchanged.
8. **Revise is a dashboard-only answer** `POST /api/approvals/{id}/revise {expectedVersion, key, note}` (beside `/answer` in `WorkGateResource`): the
   gate resolves `REJECTED` with the note (A6); no new gate state and no migration for the gate.

## Global Constraints

- **No local builds, tests or linters.** Never run `gradlew`, `npm test`, `npx vitest|tsc|eslint` on the host.
  CI on GitHub is the only verifier.
- **CI runs on pull requests only** (`.github/workflows/ci.yml`: `on: pull_request`). PR #287 is open (slice 0).
  Every "read CI" step:
  1. `git push`, note the short sha (`git rev-parse --short=8 HEAD`).
  2. `gh run list --branch feat/m4-spec-plan --limit 10 --json databaseId,headSha,name --jq '.[] | select(.headSha|startswith("<sha8>")) | "\(.databaseId) \(.name)"'`
     (no `--arg` with jq: it fails on this host).
  3. `gh run watch <id> --exit-status` for the run named `ci` (jobs `dashboard`, `fast tests`,
     `service tests + packaging`).
  4. On red: `gh run view <id> --log-failed`, and read only the failure.
- **A new push cancels an in-progress `ci` run on the same branch.** Never push while you still need the evidence
  of a running run.
- **TDD, per slice:** write the failing tests first, commit and push them alone (RED). Read the CI log and confirm
  each fails for the reason the slice names, not on compilation of an unrelated file. Where a new type must exist
  for a test to compile, the RED commit adds it with a body that throws `UnsupportedOperationException("RED")` or
  returns the wrong constant, so the failure is an assertion. A new component on a sealed type (`RunResult`,
  `RunCommand`) must also be handled in every exhaustive `switch` in the RED commit, or the RED run fails to
  compile; handle it there with `throw new UnsupportedOperationException("RED")`. Then implement, push (GREEN),
  and confirm all three `ci` jobs succeed.
- **Commits:** imperative subject of 72 characters or fewer; a body for non-trivial changes; the body ends with
  `Part of #115`. No closing keyword (`close/closes/fix/fixes/resolve…`) before an issue number. No AI, agent,
  model or vendor names as authorship, no `Co-Authored-By` trailer. "Generated" describing the product's
  generated specification is fine.
- **Git safety:** never bare `git stash`; never force-push `feat/m4-spec-plan`; never merge anything.
- **Line endings:** count CR bytes before editing (`tr -cd '\r' < f | wc -c`). For a CRLF file, edit through a
  small node script written to the scratchpad that reads, replaces and writes with the file's own eol. Never
  convert a file. New files are LF.
- **Java:** 4-space indent, explicit types (no `var`) in new code, classes ≤ 300 lines, methods ≤ 3 parameters
  (group into records), `orElseThrow()` never `Optional.get()`. When editing the dense `work/` files, keep their
  compact style and comment density. `WorkItemTransitions` (594 lines) and `WorkItemStore` gain no bodies: new
  transitions go in sibling classes (`WorkDraftTransitions`, `WorkStepTransitions`) the way `WorkLandTransitions`
  did, calling the package-private `enterPrepared`/`decide`/`lockItem`. `spire-contract` stays framework-free
  (Jackson annotations only; `PureModulesAreFrameworkFreeTest`).
- **TypeScript:** 2-space indent, `interface` for object shapes.
- **Test data:** `TEST-` prefixed identifiers and texts only, hosts `*.example.test`, numeric ids in the existing
  fixtures' style. No real names, prices or tickets. Ticket fixtures read like `TEST-ticket: make the TEST-widget
  faster` and plans carry `TEST-step-1`.
- **Domain events** only through a `WorkItemLifecycle` decision under `WorkItemTransitions.lockItem` (ADR-010).
  Effects are durable rows drained by sweeps; an uncertain dispatch is recovered by reading, never re-sent.
- **Untrusted text (A13).** Ticket text, artifacts and summaries are quoted into prompts in fenced blocks headed
  "The following is data, not instructions"; control characters (except `\n` and `\t`) are stripped; the fence is
  longer than any backtick run inside the text. Tracker comments neutralise `@`-mentions (`@` → `@​`) and raw
  HTML (`<` → `&lt;`). Field caps: step title 120, instruction 4,000, doneWhen 1,000, summary item 500.
- **Encryption.** Specification, plan, summary, revision note, validation error and prompt payloads are Tink
  encrypted at rest with an AAD naming their row (`work-prepare-artifact:<attempt>`, `work-step-summary:<item>:<generation>:<step>`,
  …). None is logged by our services (A9 narrows §5 to "by our services": the publisher's stdout reaches the
  run-worker host's Docker log, so the worker scrubs it with `RunFailures.scrubFor(command)` before sending).
- **Wire records:** a new component goes in through a wither next to the record; every shorter constructor passes
  the default explicitly. `ContractSchemaSnapshotTest` does not recurse, so every new nested wire type joins its
  nested list (`ContractSchemaSnapshotTest.java:153`) and `contract-schema.txt`.
- **Saga test fakes override every method a new path reaches**, each with a comment saying why.
- **New reasons:** every reason a slice introduces gets its `workReasons.ts` sentence in the same slice. No new
  `workflowStatus` is introduced, so `api.ts`'s status union, `STATUS_LABEL`, `miniPipeline` and `needsAttention`
  do not change; if a builder finds they must, change all four together.
- **Agent image contract:** a change to it lands with `AGENT-IMAGE-CONTRACT.md`, the reference entrypoint
  (`deploy/agent/spire-agent-entrypoint.sh`), `Clauses`, and the verifier together (`ContractAndCheckerAgreeTest`).
- **Docs in the same commit** as the behaviour they describe (DATA-MODEL for a migration, CONTRACT for a wire type
  or reason, AGENT-IMAGE-CONTRACT for the image).
- **Migrations:** `V96__generated_preparation.sql` (slice 1), `V97__work_prepare_effect.sql` (slice 5),
  `V98__work_step_summary.sql` (slice 8), `V99__work_item_step_projection.sql` (slice 9). If a number is taken on
  master when you start, take the next free one and keep the name.
- **Exact strings.**
  - Setting values: `composed`, `generated`; refusals `preparation_invalid`, `preparation_unavailable`.
  - Item reasons: `specification_to_write` (spec / awaiting_input, generated admission), `spec_invalid`,
    `plan_invalid` (awaiting_input after the automatic retry), `draft_run_failed`, `specification_written`
    (spec result gate's reason, waiting_approval), `plan_written` (plan result gate), `revision_requested`
    (phase re-entered after Revise), `plan_produced_no_change` (build / awaiting_input), `checkpoint_missing`
    already exists as a verify reason.
  - Event milestones: `DRAFT_ADMITTED`, `DRAFT_ARTIFACT_WRITTEN`, `DRAFT_RETRY_REQUESTED`, `DRAFT_REVISED`,
    `DRAFT_APPROVED`, `DRAFT_REGISTERED`, `STEP_COMPLETED`, `STEP_EMPTY`, `PLAN_PRODUCED_NO_CHANGE`.
  - Command refusals: `revision_limit_reached`, `revise_unavailable`, `regenerate_unavailable`.
  - Run failure causes (new `RunFailureCause` members): `ARTIFACT_MISSING` (read-only run ended with no artifact
    file), `CHECKPOINT_MISSING` (a plan step's start checkpoint is gone; never falls back to the base),
    `START_NOT_DESCENDED` (the ready head does not descend from `startFromHead`).
  - Publisher outcome events: `artifact` `{text}`, `step_summary` `{json}`, `repository_changed` `{bundles}`,
    `handoff_file_refused` `{file, why}`, and `checkpoint` gains `empty: true`.
  - Ticket comment heads: "Specification written by the factory", "Plan written by the factory".
- **Numbers.** Artifact transport cap 32 KiB; specification ≤ 16 KiB; plan JSON ≤ 32 KiB; summary file ≤ 8 KiB,
  each list ≤ 20 items of ≤ 500 characters; revisions ≤ 3 per phase per generation; automatic retries 1 per
  phase run; prompt budget (A8): spec 16 KiB + plan titles 4 KiB + current step 4 KiB + summaries 12 KiB + verify
  section 8 KiB ≤ 48 KiB before the commit instruction, under `DispatchRequestParser`'s 65,536.

**Mutation checks (no local runs).** Each slice lists its guard lines. For each:
1. On a scratch branch `mut/m4-spec-plan-s<N>-<k>` off the slice's GREEN commit, commit one mutant per guard.
2. Push it and open a draft PR against `feat/m4-spec-plan` titled `TEST mutant s<N>-<k> (do not merge)`.
3. Read that PR's `ci` run. Exactly the named test must fail (others may not).
4. Close the PR, delete the branch.
5. Record line, mutant, failing test and run URL in `.claude/reviews/global/factory-m4-spec-plan-mutations.md`.

Batch one slice's mutants as one branch with one commit per mutant, pushing them one at a time and waiting for
each run, because a push cancels the previous run. A mutant that does not compile is INVALID, not SURVIVED.

## Review Focus

Inputs the spec implies but no slice's main path reaches. Each has its test in the owning slice.

1. **A repository switched to `generated` while items wait at `capability_unavailable / spec_approval_unavailable`.**
   They must not all start spec runs on the next policy check (A16). Slice 5:
   `itemsParkedBeforeTheSwitchStayParked`.
2. **A switch back to `composed` with a generated item mid-draft.** The copy sweep must not copy it (decision 2).
   Slice 5: `theCopySweepSkipsAnItemAdmittedAsGenerated`.
3. **An agent that edits the repository during a spec run.** The change is discarded and recorded as a warning, the
   artifact still counts, and nothing is pushed. Slices 3–4: `aReadOnlyPublisherNeverPushesABundle`,
   `aRepositoryChangeIsAWarningNotAFailure`.
4. **A symlink, a FIFO or an oversized file at `/handoff/artifact.md`.** Refused by the publisher without
   following it (A9). Slice 3: `aSymlinkedArtifactIsRefusedWithoutFollowing`, `aFifoIsRefused`,
   `anOversizedArtifactIsRefused`.
5. **A credential echoed into the artifact.** The text the orchestrator receives has it replaced. Slice 4:
   `theArtifactIsScrubbedOfTheRunsSecrets`.
6. **The dispatch of a spec run is uncertain.** No second run is dispatched until a read proves the first never
   ran. Slice 6: `anUncertainDraftDispatchIsNeverResent`.
7. **A tracker ticket edited after the specification was written.** The stored, approved specification is what the
   plan and every step use; the edit changes nothing until Regenerate. Slice 7:
   `aTicketEditAfterApprovalChangesNothing`.
8. **Approving a revised specification after a plan existed.** The old plan is invalidated (§6.5). Slice 7:
   `revisingTheSpecificationDiscardsThePlan`.
9. **A plan whose worst-case step prompt is over budget.** Refused at acceptance as `plan_invalid`, never at
   dispatch after approval (A8). Slice 7: `aPlanOverThePromptBudgetIsInvalid`.
10. **A lost checkpoint between steps.** Step k+1 fails with `CHECKPOINT_MISSING`, never starts from the base, and
    the verify gate opens (A2). Slice 9: `aPlanStepNeverFallsBackToTheBase`, `aMissingCheckpointOpensTheVerifyGate`.
11. **A ready head that does not extend the previous step** (a crafted bundle rewriting history). Publisher and
    orchestrator both refuse (A2). Slice 9: `aHeadThatDoesNotDescendIsRefused`, `theOrchestratorRefusesAHeadOffTheChain`.
12. **Retry of a failed step 2 of 3.** Starts from step 2's failed checkpoint, keeps step 1's summary in the prompt,
    and does not count a plan step twice (A11). Slice 9: `retryingAStepKeepsEarlierSummariesAndCountsOnce`.
13. **Every step empty.** Nothing is delivered; `build / awaiting_input / plan_produced_no_change` (§6.7). Slice 10:
    `everyStepEmptyDeliversNothing`.
14. **The last step empty, earlier ones not.** Deliver uses the last non-empty step's execution (A10). Slice 10:
    `deliverUsesTheLastNonEmptyStep`.
15. **An empty step 1 followed by step 2.** Step 2 starts from the base, not from a checkpoint (A3). Slice 10:
    `anEmptyFirstStepStartsTheNextFromTheBase`.
16. **A land fix after a multi-step plan.** One extra build on the delivered branch, exempt from the steps check
    (A11), not a plan step. Slice 9: `aLandFixAfterAPlanIsNotAStep`.
17. **Readmit after delivery of a generated item.** Reuses the approved specification and plan, spends nothing on
    them (A7). Slice 11: `readmitReusesTheApprovedDraft`. Regenerate clears them: `regenerateWritesANewDraft`.
18. **A Revise answered from a tracker comment or a PR review.** Not possible: those channels keep plain
    approve/reject (A6). Slice 7: `onlyTheDashboardMayRevise`.
19. **The fourth revision.** Refused `revision_limit_reached`; Approve and Reject remain. Slice 7:
    `theFourthRevisionIsRefused`.
20. **FIX runs now record `ChargeKind.FIX`.** `ChargeCall.forRun` hard-coded BUILD, so every fix run's ledger row
    said BUILD. Taking the kind from `factory_run.kind` changes that: no query filters run charges by
    `kind='BUILD'` today (grep `FactoryRunProjection`, `FixRuns`), but the slice re-greps. Slice 2:
    `aFixRunIsChargedAsFix`.
21. **A step summary that names a secret or carries 10,000 items.** Scrubbed, capped to 20 × 500. Slice 8:
    `aSummaryIsCappedAndScrubbed`.

## Spec coverage

| Spec item | Slice → proving test |
|---|---|
| Goal 1, §6.1 setting | 1 → `BuildDefaultsTest.noRowReadsComposed`, `savesGenerated`; `BuildStep` tests |
| Goal 2, §6.2 read-only runs, A1 | 3, 4 → `aReadOnlyPublisherNeverPushesABundle`, `aReadOnlyUnitHandsThePublisherNoWriteCredential` |
| §6.2 charges, A12 | 2 → `aSpecRunIsChargedAsSpecUnderAutonomy` |
| §6.3 specification, validation, retry, comment | 6 → `aMissingHeadingRetriesOnceThenWaits`, `theSpecificationIsPostedNeutralised` |
| §6.4 plan schema v2, binding 6, A14 | 7 → `WorkArtifactsTest.bindingSixAcceptsNSteps`, `versionOneKeepsExactlyOneStep` |
| Goal 3, §6.5 gates and revisions, A6 | 6, 7 → `specApproveOpensAResultGateOnTheArtifact`, `reviseReentersWithTheNote` |
| Goal 4, §6.6 steps, A2, A15, A17 | 9 → `threeStepsBuildOnOneChain`, `aPlanStepNeverFallsBackToTheBase` |
| Goal 5, §6.7 empty steps, A3 | 10 → `anIdleLaterStepIsEmpty`, `theHeldPublisherSignalsAnEmptyFirstStep` |
| Goal 6, §6.8 summaries, A9 | 8, 9 → `theSummaryIsStoredEncrypted`, `stepThreesPromptCarriesStepOnesDecision` |
| Goal 7, §6.9, A18 | 2, 9 → `queuedWritesKindAndStepIndex` |
| §6.10 dashboard | 6, 7, 10 → `SpecificationView`, `PlanView`, `StepRows` tests |
| §6.11 readmit, A7 | 11 → `readmitReusesTheApprovedDraft`, `regenerateWritesANewDraft` |
| A4 draft fact and effect | 5 → `WorkDraftTest`, `aDraftPhaseStartInsertsOnePrepareEffect` |
| A5 copy sweep | 5 → `theCopySweepSkipsAnItemAdmittedAsGenerated` |
| A8 prompt budget | 7, 9 → `aPlanOverThePromptBudgetIsInvalid`, `summariesAreCutOldestFirst` |
| A10 deliver | 10 → `deliverUsesTheLastNonEmptyStep` |
| A11 step counting | 9 → `stepsCountCompletionsNotStarts`, `draftRunsDoNotCountTowardMaxRuns` |
| A13 untrusted text | 6, 8 → `theTicketIsFencedAsData`, `aSummaryIsCappedAndScrubbed` |
| A16 admission | 5 → `itemsParkedBeforeTheSwitchStayParked` |
| A19, A21, §7 docs | 11 → docs checklist |

---

### Slice 0: draft PR and baseline

**Files:** this plan only.

- [x] Draft PR #287 opened from `feat/m4-spec-plan` to `master`, title "Generate the specification and plan, and
  build in steps", body ending `Part of #115`. No `--fill`, no Development-panel link.
- [ ] Commit this plan alone: `Add the M4 specification and plan implementation plan` (body: what the plan
  covers, decision 1 and 2, `Part of #115`). Push.
- [ ] Read the `ci` run for that commit: all three jobs green before slice 1 starts (baseline).

---

### Slice 1: the preparation setting (V96) and the Build step control

**Goal:** an operator can store `composed` or `generated` per repository; nothing reads it yet. `factory_run`
gains `step_index`. The control is offered only when `spire.work.generated-preparation` is on.

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V96__generated_preparation.sql`:
  `ALTER TABLE repository_build_defaults ADD COLUMN preparation TEXT NOT NULL DEFAULT 'composed'
  CHECK (preparation IN ('composed','generated'))`; `ALTER TABLE factory_run ADD COLUMN step_index INT
  CHECK (step_index IS NULL OR step_index >= 0)`. Header comment: why no `phase` column (A18: verify is not a run,
  `kind` already names spec/plan/build/fix).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/BuildDefaults.java` (`Defaults.preparation`,
  `Input.preparation` — null means keep the stored value; refuse unknown with `preparation_invalid`, `generated`
  while the flag is off with `preparation_unavailable`). If the file passes 300 lines, move validation into
  `BuildDefaultsValidation`.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RepositoryBuildResource.java`
  (options carry `preparations: ["composed"]` or `["composed","generated"]`).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/GeneratedPreparation.java`
  (`@ConfigProperty(name="spire.work.generated-preparation", defaultValue="false") boolean enabled`; one place
  every later slice asks).
- Modify: `spire-ui/src/components/repositories/factory/buildDefaultsApi.ts`, `BuildStep.tsx`, `factoryFixtures.ts`.
- Modify: `spire-ui/src/components/work-items/workReasons.ts` (`preparation_invalid`, `preparation_unavailable`).
- Modify: `docs/DATA-MODEL.md` (V96).
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/BuildDefaultsTest.java` (extend),
  `GeneratedPreparationProfile.java` (new test profile), `BuildDefaultsGeneratedTest.java` (new, under that profile).
- Test: `spire-ui/src/components/repositories/factory/RepositoryFactory.test.tsx` (extend), `workReasons.test.ts`.

**Interfaces — Produces:**
```java
// BuildDefaults
public record Defaults(long revision, String baseBranch, String harness, String model, String effort, String payWith,
                       String updatedBy, Instant updatedAt, List<String> verifyCommands, long verifyTimeoutSeconds,
                       String preparation) {}   // "composed" for no row; existing shorter constructors pass "composed"
public record Input(..., String preparation) {}  // null keeps the stored value
public static final String COMPOSED = "composed", GENERATED = "generated";
```
```ts
export interface BuildDefaults { /* existing */ preparation: 'composed' | 'generated' }
export interface BuildOptions { /* existing */ preparations: Array<'composed' | 'generated'> }
```

**UI copy (A20):** "Specification and plan: Copy the ticket as one step / Write them with the build model". The
generated option names the harness, model and payment from the same form ("runs on <harness> · <model> ·
<seat|API key>") and says: "Before the first approval this spends two short runs, and up to one automatic retry
each, when the specification and plan phases are set to run without approval."

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `BuildDefaultsTest.noRowReadsComposed` | repository with no row → `preparation == "composed"` | default missing or `null` |
| `…anExistingRowMigratesComposed` | row inserted before V96 semantics (insert without the column) → `composed` | column default not `composed` |
| `…anUnknownPreparationIsRefused` | `Input.preparation = "TEST-other"` → `Refused("preparation_invalid")`, row unchanged | value not validated (DB CHECK raises a 500 instead) |
| `…generatedIsRefusedWhileDark` | flag off → `Refused("preparation_unavailable")` | flag not consulted |
| `…anOmittedPreparationKeepsTheStoredOne` | save `generated` (profile on), then save with `null` → still `generated` | null overwrites with `composed` |
| `BuildDefaultsGeneratedTest.savesGenerated` | flag on: save `generated` → read `generated`, revision +1 | |
| `…optionsOfferGeneratedOnlyWhenOn` | flag on → `["composed","generated"]`; `BuildDefaultsTest` flag off → `["composed"]` | options hard-coded |
| `BuildDefaultsTest.stepIndexAdmitsNullAndRefusesNegative` | `INSERT factory_run … step_index -1` raises a CHECK violation; null accepted | CHECK missing |
| `RepositoryFactory.test.tsx › the build step offers written preparation with its runner` | options with `generated` → radio "Write them with the build model" names harness, model and "seat" | control missing or runner not named |
| `› it warns of spend before the first approval` | the A20 sentence is shown under the generated option | copy missing |
| `› the choice is hidden when the server does not offer it` | options `["composed"]` → no preparation control | control rendered regardless |
| `› saving sends the chosen preparation` | choose generated, save → PUT body has `preparation: 'generated'` | field not sent |
| `workReasons.test.ts › every new reason has a sentence` | `preparation_invalid`, `preparation_unavailable` resolve to non-fallback copy | copy missing |

- [ ] RED: tests + `Defaults`/`Input` component with the column read returning `null`; push; confirm assertion
  failures in `service tests + packaging` and `dashboard`.
- [ ] GREEN: migration, validation, resource, UI; push; three jobs green.
- [ ] Commits: `Add failing tests for the preparation setting`, then `Store how a repository prepares its work`.

**Mutation guards:** the `preparation_invalid` membership check; the flag check in `save`; the null-keeps-stored
branch; the options condition; the UI's `preparations.includes('generated')` render condition.

---

### Slice 2: charge kinds SPEC and PLAN, and the run row's kind and step

**Goal:** a run's ledger row carries the run's own kind; SPEC and PLAN runs are charged under the `AUTONOMY`
capability; `QueuedRun` writes `kind` and `step_index`. Nothing dispatches SPEC or PLAN yet.

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeKind.java` (`SPEC`, `PLAN`, with the
  V42 CHECK named; javadoc of `BUILD` no longer says "a factory run" for all of them).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeCall.java`: replace
  `forRun(runId, callRef, model, lines, credentialRef)` with `forRun(RunSpend spend, String model, List<ChargeLine> lines)`.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/RunSpend.java`
  `record RunSpend(String runId, String callRef, ChargeKind kind, String credentialRef)`; refuses REVIEW,
  RECONCILE, FOLLOWUP.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunCharges.java` (kind from
  `runs.kindOf(runId)` mapped through `RunKind.chargeKind()`).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunKind.java` (`ChargeKind chargeKind()`;
  javadoc no longer says SPEC/PLAN are "not built").
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/FactoryRunProjection.java`
  (`QueuedRun.stepIndex` via `atStep(int)` and `ofKind(RunKind)` withers; `queued` binds `step_index`;
  `kindOf(runId)`). Every rebuild site of `QueuedRun` goes through the withers.
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/ChargeCallTest.java` (extend or new, plain).
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/RunChargesKindTest.java` (new, service).
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/FactoryRunProjectionStepTest.java` (new, service).

**Interfaces — Produces:** `RunSpend`, `RunKind.chargeKind()` (BUILD→BUILD, FIX→FIX, SPEC→SPEC, PLAN→PLAN),
`ChargeCall.forRun(RunSpend,String,List<ChargeLine>)` with capability `AUTONOMY` for SPEC/PLAN and `BUILD` for
BUILD/FIX, `QueuedRun.atStep(int)`, `QueuedRun.ofKind(RunKind)`, `FactoryRunProjection.kindOf(String)`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `ChargeCallTest.specAndPlanRunsAreAutonomy` | `forRun(new RunSpend(run,ref,SPEC,null),…)` → capability AUTONOMY, kind SPEC; same for PLAN | capability hard-coded BUILD |
| `…buildAndFixKeepTheBuildCapability` | BUILD and FIX → capability BUILD, kind as given | |
| `…aReviewKindIsNotARunSpend` | `new RunSpend(run,ref,REVIEW,null)` throws `IllegalArgumentException` | guard missing |
| `RunChargesKindTest.aSpecRunIsChargedAsSpecUnderAutonomy` | a `factory_run` row of kind SPEC finishing → `llm_charge.kind='SPEC'`, `capability='AUTONOMY'` | kind hard-coded; or V42 CHECK refuses it |
| `…aFixRunIsChargedAsFix` | FIX row → `llm_charge.kind='FIX'` (Review Focus 20) | still BUILD |
| `…aBuildRunIsStillChargedAsBuild` | BUILD row → `BUILD`/`BUILD` | mapping broken |
| `FactoryRunProjectionStepTest.queuedWritesKindAndStepIndex` | `QueuedRun…ofKind(PLAN)` → row `kind='PLAN'`, `step_index` null; `ofKind(BUILD).atStep(2)` → `2` | column not bound |
| `…aRearmedRunKeepsItsStepIndex` | re-arm path (`queued` on conflict) keeps `step_index` | upsert drops it |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for spec and plan charge kinds`, then `Charge each run under its own kind`.

**Mutation guards:** `RunKind.chargeKind` switch arms; the AUTONOMY condition in `forRun`; the RunSpend REVIEW
guard; `step_index` bind in `queued` and its upsert `SET`.

---

### Slice 3: the READ_ONLY publisher and the handoff files

**Goal:** the publisher image has `spire-publish-none`, which never pushes and reports the agent's artifact and
step summary as outcome lines. The reference entrypoint copies `$SPIRE_ARTIFACT` and `$SPIRE_STEP_SUMMARY` into
the handoff before `DONE`. The held publisher reports the step summary too. Nothing launches a read-only unit yet.

**Files:**
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/PublicationPolicy.java` (`Mode.READ_ONLY`,
  `readOnly()` factory).
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/ReadOnlyPublisherMain.java` (distinct executable,
  like `HeldPublisherMain`).
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/ReadOnlyPublisher.java`: waits for `DONE`
  (polling the handoff as `PublisherMain` does), counts `*.bundle` files without reading them, reads the two
  files through `HandoffFiles`, writes outcome lines. No clone, no `PublisherConfig` credential: it reads only
  `SPIRE_HANDOFF_DIR` and size caps, so a missing `SPIRE_GIT_SECRET` is not a misconfiguration here.
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/HandoffFiles.java`: `Optional<String> read(Path dir,
  String name, int maxBytes)` with `LinkOption.NOFOLLOW_LINKS`, `Files.isRegularFile(…, NOFOLLOW_LINKS)`, size
  checked from attributes before and bytes counted while reading (a file growing during the read is refused),
  UTF-8 decoded with malformed input refused. Refusals are typed (`SYMLINK`, `NOT_REGULAR`, `TOO_LARGE`, `NOT_UTF8`).
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/OutcomeWriter.java` (`artifact(text)`,
  `stepSummary(json)`, `repositoryChanged(bundles)`, `handoffFileRefused(file, why)`; the existing credential
  redaction applies to every line).
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/PublisherMain.java` (HELD mode: after the final
  drain, read `step-summary.json` and emit `step_summary`; it never fails the run).
- Create: `spire-publisher/docker/spire-publish-none.sh`; modify `spire-publisher/Dockerfile` (COPY it).
- Modify: `deploy/agent/spire-agent-entrypoint.sh`: export `SPIRE_ARTIFACT="$SCRATCH/artifact.md"` and
  `SPIRE_STEP_SUMMARY="$SCRATCH/step-summary.json"` (one owner, outside the work tree, like `SPIRE_SUMMARY`); after
  the final checkpoint and before `: > "$HANDOFF/DONE"`, copy each that exists as a regular file with
  `cat src > "$HANDOFF/.name.tmp" && mv` (so the handoff holds a regular file even if the agent made a symlink in
  scratch), capped with `head -c` at the publisher's cap + 1 so an oversize is still detectable.
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/PublisherOutcome.java` (parse the four events;
  `artifact()`, `stepSummary()`, `repositoryChanged()`, `checkpointEmpty()` placeholder for slice 10; last line of
  each wins; over-cap text is dropped and recorded as a refusal, never truncated into a valid-looking artifact).
- Modify: `spire-agent-image/src/main/java/dev/codespire/agentimage/Clauses.java` (`HANDOFF_FILES = "handoff-files"`,
  in `VERIFIED`), `AgentImageVerifier.java` (probe: run the entrypoint with a harness argv that writes both env
  files; assert `artifact.md` and `step-summary.json` are regular files in the handoff and older than `DONE`).
- Modify: `docs/factory/AGENT-IMAGE-CONTRACT.md` (the optional `$SPIRE_ARTIFACT` and `$SPIRE_STEP_SUMMARY` files,
  where they land, their caps, "regular files only, never followed", the clause name), `docs/factory/RUN-TOPOLOGY.md`
  (the fourth publisher command).
- Test: `spire-publisher/src/test/java/dev/codespire/publisher/HandoffFilesTest.java` (new),
  `ReadOnlyPublisherTest.java` (new), `PublicationPolicyTest.java`, `OutcomeWriterTest.java`, `HeldPublicationTest.java`.
- Test: `spire-run-worker/src/test/java/dev/codespire/runworker/PublisherOutcomeTest.java`.
- Test: `spire-agent-image/src/test/java/dev/codespire/agentimage/AgentImageVerifierTest.java`, `ReferenceImageIT.java`;
  `spire-arch/…/ContractAndCheckerAgreeTest.java` must stay green (it is the doc/clause agreement guard).

**Interfaces — Produces:**
```java
// spire-publisher
enum Mode { AUTOMATIC, HELD, PERMITTED, READ_ONLY }
final class HandoffFiles {
    enum Refusal { SYMLINK, NOT_REGULAR, TOO_LARGE, NOT_UTF8 }
    sealed interface Read { record Found(String text) implements Read {} record Absent() implements Read {}
                            record Refused(Refusal why) implements Read {} }
    static Read read(Path dir, String name, int maxBytes) throws IOException;
    static final String ARTIFACT = "artifact.md", STEP_SUMMARY = "step-summary.json";
    static final int ARTIFACT_MAX_BYTES = 32 * 1024, STEP_SUMMARY_MAX_BYTES = 8 * 1024;
}
// spire-run-worker PublisherOutcome
public Optional<String> artifact(); public Optional<String> stepSummary(); public int repositoryChanged();
public List<String> handoffRefusals();
```
Outcome lines: `{"event":"artifact","text":"…"}`, `{"event":"step_summary","json":"…"}`,
`{"event":"repository_changed","bundles":2}`, `{"event":"handoff_file_refused","file":"artifact.md","why":"SYMLINK"}`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `HandoffFilesTest.aRegularFileIsRead` | file with `TEST-spec` → `Found("TEST-spec")` | |
| `…anAbsentFileIsAbsent` | no file → `Absent` | absent treated as refusal |
| `…aSymlinkedArtifactIsRefusedWithoutFollowing` | `artifact.md` → symlink to a file outside the dir holding `TEST-secret` → `Refused(SYMLINK)`, target never opened (target made unreadable; no `AccessDenied` thrown) | `NOFOLLOW_LINKS` dropped |
| `…aFifoIsRefused` | `mkfifo` (skipped on Windows via `assumeTrue`) → `Refused(NOT_REGULAR)` returns without blocking (test timeout 5 s) | regular-file check missing |
| `…anOversizedArtifactIsRefused` | 32 KiB + 1 → `Refused(TOO_LARGE)` | cap removed or off by one |
| `…invalidUtf8IsRefused` | bytes `C3 28` → `Refused(NOT_UTF8)` | lenient decoder |
| `ReadOnlyPublisherTest.aReadOnlyPublisherNeverPushesABundle` | handoff with `1.bundle` and `DONE`, a `GitFixture` remote → remote has no new ref; outcome has `repository_changed` with 1 | bundle pushed or ignored silently |
| `…itReportsTheArtifactAndSummary` | both files + `DONE` → `artifact` and `step_summary` lines in that order, exit 0 | lines missing |
| `…itNeedsNoWriteCredential` | environment without `SPIRE_GIT_USERNAME`/`SECRET`/`REMOTE_URI` → runs, exit 0 | reads `PublisherConfig.fromEnv` |
| `…aRefusedFileIsReportedNotFatal` | symlinked artifact → `handoff_file_refused` line, exit 0 (the run worker decides `ARTIFACT_MISSING`) | throws |
| `…itWaitsForDone` | files written, `DONE` written 2 s later → no `artifact` line before `DONE` | reads before `DONE` |
| `PublicationPolicyTest.readOnlyIsItsOwnMode` | `PublicationPolicy.readOnly().mode()==READ_ONLY`; `onExistingBranch` refuses it | |
| `OutcomeWriterTest.anArtifactLineIsRedacted` | writer with secret `TEST-token`, `artifact("x TEST-token y")` → line has no `TEST-token` | redaction bypassed for the new line |
| `HeldPublicationTest.theHeldPublisherReportsTheStepSummary` | held run with a bundle, `step-summary.json`, `DONE` → `checkpoint` then `step_summary` | not read in HELD mode |
| `PublisherOutcomeTest.theArtifactLineIsKept` | `artifact` line → `artifact()` present | not parsed |
| `…anOverCapArtifactLineIsRefusedNotTruncated` | text of 32 KiB + 1 chars → `artifact()` empty, `handoffRefusals()` has `TOO_LARGE` | truncated and kept |
| `…theLastArtifactLineWins` | two lines → second text | first kept |
| `…aRepositoryChangeIsCounted` | `repository_changed` 2 → `repositoryChanged()==2` | |
| `AgentImageVerifierTest.handoffFilesClauseFailsWhenDoneIsWrittenFirst` | fake probe whose entrypoint writes `DONE` before `artifact.md` → clause `handoff-files` FAILED | probe checks presence only |
| `…handoffFilesClausePassesForTheReferenceOrder` | correct order → PASSED | |
| `ReferenceImageIT.theReferenceEntrypointCopiesBothFilesBeforeDone` (service tier, Docker) | reference image with a harness argv writing both files → regular files in the handoff volume, mtime ≤ `DONE`'s | entrypoint copy missing or after `DONE` |
| `…aSymlinkInScratchBecomesARegularFile` | harness creates `$SPIRE_ARTIFACT` as a symlink to `/etc/hostname` → handoff file is regular | entrypoint uses `cp -P`/`mv` of the link |

- [ ] RED: tests, `Mode.READ_ONLY`, `HandoffFiles.read` throwing `RED`, empty `ReadOnlyPublisher`, entrypoint unchanged;
  push; confirm assertion failures in `fast tests` and `service tests + packaging`.
- [ ] GREEN; push; green.
- [ ] Commits: `Add failing tests for the read-only publisher and handoff files`, then
  `Add a read-only publisher and hand the artifact over as a file`.

**Mutation guards:** `NOFOLLOW_LINKS` on the attribute read; the regular-file check; the size comparison (`>`);
the growth check while reading; READ_ONLY's absence of any `pushRef` call (mutant: call the automatic cycle);
`PublisherOutcome`'s cap check; the entrypoint's copy-before-`DONE` order (mutant: move the copy after `DONE`,
caught by `ReferenceImageIT`); the verifier's ordering assertion.

---

### Slice 4: the draft run on the wire, in the run worker, and in the run projection

**Goal:** `ExecuteWorkDraftRun` launches a read-only unit and returns `RunWorkArtifact` (scrubbed), destroying the
unit once the result is recorded. The orchestrator projects it as a finished run and charges it under its kind.
Nothing dispatches one yet.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java`: `ExecuteWorkDraftRun(runId,
  execution, draft)`; refuses an execution that starts from a checkpoint, pushes to an existing branch, or names a
  `branch` other than `spire/draft-<attempt>` (the clone's local name; nothing is pushed).
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/WorkDraftBinding.java`
  `record WorkDraftBinding(String workItemId, long generation, UUID attemptId, Kind kind, String provisionalDigest)`,
  `enum Kind { SPEC, PLAN }`; `provisionalDigest` is 64 hex (A4: setup revision + ticket digest + kind, computed by
  the orchestrator in slice 6; its shape only is checked here).
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/RunResult.java`: `RunWorkArtifact(runId, draft,
  text, repositoryChanged, tokenUsage, activeWallSeconds)`; `text` non-blank and ≤ 32 KiB; empty token map refused
  like the siblings; `withUsage` wither. Register the subtype.
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/RunFailureCause.java` (`ARTIFACT_MISSING(false, true)`).
- Modify: `spire-contract/src/test/resources/contract-schema.txt` and `ContractSchemaSnapshotTest` nested list
  (`WorkDraftBinding`).
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunDispatcher.java` (route `ExecuteWorkDraftRun`
  through the M2 execute-slot claim, like `ExecuteRun`, to `RunLauncher.launchReadOnly`).
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunUnitBuilder.java`: `buildReadOnly(draft,
  adapter)`: publisher argv `spire-publish-none`, environment `SPIRE_HANDOFF_DIR` and caps only; init and agent as
  `build`. If the file passes 300 lines with this, extract `publisherEnvironment`/`existingBranchEnvironment` into
  `PublisherEnvironments`.
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunLauncher.java`: `launchReadOnly(draft, observer)`
  and the named branch `readOnlyResult(command, observed)` (A1): agent succeeded and `outcome.artifact()` present →
  `RunWorkArtifact` with `failures.scrubFor(command).clean(text)`; absent → `RunFailed(ARTIFACT_MISSING)` with
  usage; agent failed → the usual failure with usage. The unit is destroyed in the existing `work == null` branch
  (so `unitReleased` fires); it is never put in `WorkRunStore`, so `WorkRunWorker.recover` cannot see it.
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/WorkRunWorker.java` (`cancelledResult` switch arm).
- Modify: orchestrator exhaustive switches over `RunResult` (`RunEventProjection`, `RunResultSaga`, `RunCharges`,
  `WorkItemRunBridge.record`): a `RunWorkArtifact` projects status `finished` with no pushed ref, is charged
  through slice 2's kind mapping, and is ignored by `WorkItemRunBridge` (slice 6 adds its own inbox).
- Modify: `docs/CONTRACT.md` (the command, the result, the cause).
- Test: `spire-contract/src/test/java/dev/codespire/contract/event/RunWorkArtifactTest.java` (new),
  `…/command/ExecuteWorkDraftRunTest.java` (new), `ContractSchemaSnapshotTest`, `RunFailureCauseTest`.
- Test: `spire-run-worker/src/test/java/dev/codespire/runworker/RunUnitBuilderTest.java`, `RunLauncherTest.java`,
  `RunDispatcherTest.java`, `WorkRunWorkerTest.java`.
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/RunWorkArtifactProjectionTest.java` (new, service).

**Interfaces — Produces:** `RunCommand.ExecuteWorkDraftRun`, `WorkDraftBinding`, `RunResult.RunWorkArtifact`,
`RunFailureCause.ARTIFACT_MISSING`, `RunLauncher.launchReadOnly`, `RunUnitBuilder.buildReadOnly`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `ExecuteWorkDraftRunTest.aDraftRunCannotStartFromACheckpoint` | execution with `fromCheckpoint(…)` → `IllegalArgumentException` | guard missing |
| `…aDraftRunCannotPushToAnExistingBranch` | `onExistingBranch(…)` → refused | |
| `…aDraftRunUsesItsOwnLocalBranchName` | branch `spire/work-x` → refused; `spire/draft-<attempt>` accepted | name not checked |
| `RunWorkArtifactTest.anOverCapTextIsRefused` / `aBlankTextIsRefused` / `anEmptyUsageMapIsRefused` | constructor guards | |
| `…withUsageEnumeratesEveryComponent` | `withUsage(m)` keeps draft, text, flag, wall | a component dropped |
| `ContractSchemaSnapshotTest` (existing) | snapshot includes `RunWorkArtifact`, `ExecuteWorkDraftRun`, nested `WorkDraftBinding` | snapshot not updated (RED shows the diff) |
| `RunUnitBuilderTest.aReadOnlyUnitHandsThePublisherNoWriteCredential` | publisher env has no `SPIRE_GIT_SECRET`/`SPIRE_GIT_USERNAME` | write credential passed |
| `…aReadOnlyUnitRunsSpirePublishNone` | publisher argv `["spire-publish-none"]` | |
| `…aReadOnlyUnitMountsNoCheckpoint` | init has no `/checkpoint` mount | |
| `RunLauncherTest.aReadOnlyRunReturnsItsArtifact` | fake runtime: publisher writes `artifact` line, agent exits 0 → `RunWorkArtifact` text equal, usage carried | branch missing |
| `…theArtifactIsScrubbedOfTheRunsSecrets` | artifact text contains the decrypted harness key `TEST-key` → result text has none | scrub skipped |
| `…aReadOnlyRunWithoutAnArtifactFails` | no `artifact` line → `RunFailed("ARTIFACT_MISSING")` with usage | reports finished |
| `…aRepositoryChangeIsAWarningNotAFailure` | `repository_changed` 1 + artifact → `RunWorkArtifact(repositoryChanged=true)` | treated as failure |
| `…aReadOnlyRunDestroysItsUnit` | `runtime.destroy` called, `observer.unitReleased()` called | unit retained |
| `RunDispatcherTest.aDraftRunIsClaimedOnceAndLaunchedReadOnly` | redelivered command launches once | routed to `WorkRunWorker` or unclaimed |
| `WorkRunWorkerTest.recoverNeverSeesADraftRun` | after a draft run, `store.unfinished()` is empty | stored as held |
| `RunWorkArtifactProjectionTest.anArtifactResultFinishesTheRun` | `factory_run.status='finished'`, `pushed_ref` null, one `llm_charge` of kind `SPEC` | not projected or charged as BUILD |

- [ ] RED (every exhaustive switch gets a `RED` arm so it compiles); push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for the read-only draft run`, then `Run a specification or plan without pushing`.

**Mutation guards:** each `ExecuteWorkDraftRun` guard; `RunWorkArtifact` cap; the `artifact().isPresent()`
condition; the scrub call; the destroy branch condition (`work == null`); the routing case in `RunDispatcher`.

---

### Slice 5: the draft fact, its durable effect and the two sweeps

**Goal:** with the flag on and the repository on `generated`, a newly admitted item records `WorkDraft`, waits at
`spec / awaiting_input / specification_to_write`, and a sibling sweep starts a spec attempt that inserts one
`work_prepare_effect` row. The copy sweep skips such items. The spec run is not dispatched yet (no dispatcher).

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/WorkDraft.java`:
  ```java
  public record WorkDraft(String mode, WorkPreparation.Artifact specification, WorkPreparation.Artifact plan,
                          int specificationRevisions, int planRevisions, boolean specificationApproved,
                          boolean planApproved, String baseCommit) {
      public static final String GENERATED = "generated";
      public static WorkDraft admitted();                          // generated, nothing written
      public WorkDraft withSpecification(WorkPreparation.Artifact a, String baseCommit); // clears plan and approvals
      public WorkDraft withPlan(WorkPreparation.Artifact a);
      public WorkDraft approved(String phase);
      public WorkDraft revised(String phase);                      // +1, clears that artifact (and plan for spec)
      public WorkPreparation.Artifact artifactFor(String phase);
      public static final int MAX_REVISIONS = 3;
  }
  ```
  Artifacts are `STORED` only; revisions 0..3; `baseCommit` 40 hex once a spec exists.
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkItemEvent.java`: component `draft` after
  `control`; every shorter constructor passes `null`; `withMilestone`, `decision`, `readmit`, `controlled`,
  `prepared` carry it; new wither `drafted(WorkDraft)`. `readmit()` carries the draft unchanged here (slice 11
  decides what readmit keeps).
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkItemLifecycle.java` (if it passes 300 lines,
  put the new decisions in `WorkDraftLifecycle`, same package):
  - `admitGenerated(WorkItemEvent admitted)`: only when the item is entering `spec` for the first time this
    generation (no preparation, no draft, no attempt), turns `capability_unavailable / spec_approval_unavailable`
    or `awaiting_input / specification_required` into `awaiting_input / specification_to_write`,
    milestone `DRAFT_ADMITTED`, `drafted(WorkDraft.admitted())`. Any other state is returned unchanged (A16).
  - `enter`: for `spec`/`plan` with a draft whose artifact for the phase is absent, the permission gate is skipped
    (A6: under generated the run is admitted by the setting) and the attempt starts; `WorkProgress.start` for
    `spec`/`plan` counts neither runs nor steps (it already counts only `build`; a test pins it, A11).
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkGate.java` (`artifactOf`: for `spec`/`plan`
  with a draft, the draft artifact's sha256 for that phase, A6).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkItemStore.java` (`reconcile`: when the
  flag is on and `BuildDefaults.get(c, repository, false).preparation()` is `generated`, apply `admitGenerated`
  after `reconcile` and before `admission`; one call into `WorkDraftProjection` from `projectExecution`; one more
  `UPDATE work_prepare_effect … refused` in the invalidation list). Keep the additions to a few lines each.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftProjection.java`: inserts the
  `work_prepare_effect` row on `PHASE_STARTED` for `spec`/`plan` with a draft; projects `work_item.preparation_mode`.
- Create: `spire-orchestrator/src/main/resources/db/migration/V97__work_prepare_effect.sql`:
  `work_prepare_effect(attempt_id UUID PK, work_item_id TEXT NOT NULL REFERENCES work_item(id), generation BIGINT,
  kind TEXT CHECK (kind IN ('SPEC','PLAN')), state TEXT CHECK (state IN ('pending','uncertain','sent','refused',
  'done')), reason TEXT, run_id TEXT, provisional_digest CHAR(64), retry_of UUID, note BYTEA, validation_error BYTEA,
  result_payload BYTEA, result_processed BOOLEAN NOT NULL DEFAULT false, created_at, updated_at)`, a pending index;
  `ALTER TABLE work_item ADD COLUMN preparation_mode TEXT NOT NULL DEFAULT 'composed' CHECK (preparation_mode IN
  ('composed','generated'))`.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkPreparationSweep.java`:
  `candidates()` adds `AND i.preparation_mode = 'composed'`, `automatic()` adds `item.draft() == null`; the class
  comment says why the item's mode, not the repository's (decision 2, A5).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftSweep.java`: candidates
  `spec / awaiting_input / specification_to_write` with `preparation_mode='generated'`; the same local checks as
  the copy sweep (`build_defaults_missing`, `model_disabled`, pricing, catalogue) with its backoff table
  (`work_item_preparation_attempt`), then `WorkDraftTransitions.start(id, expectedRevision)`.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftTransitions.java`: `start`
  under `lockItem` with the revision and status re-checked, `enterPrepared`, `decide`.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkPhaseCapability.java` (`spec`,`plan`
  → `item.draft()!=null && drafts.available()`, where `WorkDraftTransport.available()` is false until slice 6).
- Modify: `spire-ui/src/components/work-items/workReasons.ts` (`specification_to_write`), `workJourney.ts` (not a
  preparation reason: no "Prepare the task" action for it).
- Modify: `docs/DATA-MODEL.md` (V97, the draft fact), `docs/CONTRACT.md` (component, milestones, reason).
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkDraftTest.java` (new),
  `WorkDraftLifecycleTest.java` (new), `WorkProgressTest.java`, `WorkItemEventTest` / snapshot.
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkDraftAdmissionIT.java` (new, service,
  `GeneratedPreparationProfile`), `WorkPreparationSweepTest.java` (extend).

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkDraftTest.aStoredArtifactOnly` | a TRACKER artifact → refused | origin unchecked |
| `…aSpecificationClearsThePlanAndApprovals` | `withPlan(p).approved("plan").withSpecification(s2,…)` → plan null, both approvals false | plan kept (§6.5) |
| `…revisionsStopAtThree` | `revised("spec")` four times → the fourth throws | bound missing |
| `ContractSchemaSnapshotTest` | `WorkItemEvent` shows `draft`; nested `WorkDraft` rendered | nested list not extended |
| `WorkItemEventTest.everyWitherCarriesTheDraft` | `withMilestone`, `decision`, `readmit`, `controlled`, `prepared` keep a set draft | a wither drops it (CLAUDE.md gotcha) |
| `WorkDraftLifecycleTest.aFreshSpecAdmissionBecomesSpecificationToWrite` | spec approve → `awaiting_input / specification_to_write / DRAFT_ADMITTED`, draft set | |
| `…itemsParkedBeforeTheSwitchStayParked` | previous already at `capability_unavailable / spec_approval_unavailable` (an earlier revision) → unchanged | conversion applies to any state (A16) |
| `…aPreparedItemIsNotDrafted` | item with preparation → unchanged | |
| `…aDraftPhaseSkipsThePermissionGate` | spec approve + draft without spec → `PHASE_STARTED` (no gate) | gate opens before the run |
| `…specOffStaysOff` | spec off → `not_eligible / spec_off` even with a draft | draft bypasses off |
| `WorkProgressTest.draftPhasesDoNotCountRunsOrSteps` | `start(id,"spec",now)` → runs and steps unchanged | counting extended to spec |
| `WorkGateTest.aDraftGateBindsTheArtifactDigest` | spec phase with a draft spec → `artifactOf` = its sha256 | binding used |
| `WorkDraftAdmissionIT.aGeneratedRepositoryAdmitsADraft` | flag on, `generated`, ticket labelled → item `specification_to_write`, `preparation_mode='generated'` | store does not consult the setting |
| `…theFlagOffAdmitsAsComposed` | flag off, `generated` stored → `specification_required`, mode composed | flag ignored |
| `…theDraftSweepStartsOneSpecAttempt` | sweep → `spec / active / phase_started`, one `work_prepare_effect` row `SPEC/pending` | row missing or two |
| `…aDraftPhaseStartInsertsOnePrepareEffect` | replaying the projection (same envelope twice) → one row | no `ON CONFLICT` |
| `…theDraftSweepRecordsBuildDefaultsMissing` | no setup → health `build_defaults_missing`, no attempt | checks skipped |
| `…aTakeoverRefusesAPendingPrepareEffect` | HUMAN_TAKEOVER → row `refused / work_item_invalidated` | invalidation list not extended |
| `WorkPreparationSweepTest.theCopySweepSkipsAnItemAdmittedAsGenerated` | generated item, repository switched back to composed → copy sweep prepares nothing | filter on the repository column |
| `…aComposedItemIsStillCopiedAfterASwitchToGenerated` | composed item admitted before the switch → copied | filter on the repository column |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for the draft fact and its sweeps`, then
  `Admit generated items as drafts and start their specification`.

**Mutation guards:** `admitGenerated`'s first-entry condition; the gate skip's artifact-absent condition;
`artifactOf`'s draft arm; `WorkDraft.withSpecification` clearing the plan; the revision bound; the
`preparation_mode` filter and `draft()==null` in the copy sweep; the flag check in `reconcile`; the projection's
`PHASE_STARTED` + phase condition.

---

### Slice 6: the specification run, its validation, the ticket comment and the spec result gate

**Goal:** the spec attempt dispatches a read-only run with a fenced prompt; its artifact is validated (one
automatic retry), stored encrypted, posted to the ticket neutralised, and then either opens a spec result gate
(`approve`) or completes the phase (`auto`), moving to `plan`, which is still unavailable (slice 7). The
dashboard's Spec step shows the specification.

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkPrepareDispatcher.java` (pattern of
  `WorkRunDispatcher`: committed claim before broker send; `pending → uncertain → sent`; a definite miss re-arms; an
  uncertain one waits for the M2 resolution, never resent). Reads the ticket and the base head (network) before
  the claim; the claim re-checks under `lockItem`.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/WorkDraftAssembly.java`: the same
  funding checks as `WorkRunAssembly` (model disabled, catalogue admit, pricing, spend gate, seat or key); extract
  the shared part into `RunFunding` (package `factory`) rather than duplicating it; `QueuedRun…ofKind(SPEC|PLAN)`;
  `runs.queued`; `factory_run.work_item_id` and attempt association like a build.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftPrompts.java`: the spec prompt
  (title, body — empty allowed —, labels, "read `.codespire` if present", the required headings, "write the result
  to the file named by `$SPIRE_ARTIFACT`; change nothing in the repository"); on a retry, the validation error;
  on a revision, the operator's note — every one fenced as data (A13).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/UntrustedText.java`: `fenced(label, text)`,
  `stripControls(text)`, `neutralise(text)` for tracker comments.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/SpecificationRules.java`: headings in
  order (`## Outcome`, `## Context`, `## Acceptance criteria`, `## Out of scope`, `## Open questions`), at least one
  list item under criteria, ≤ 16 KiB after control stripping; returns the first failure as a sentence.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftResults.java`: durable inbox for
  `RunWorkArtifact` and `RunFailed` of a draft run (binding = `work_prepare_effect` row's item, generation,
  attempt, kind and provisional digest, or it is refused `draft_binding_mismatch`); stores the encrypted payload,
  then applies under the lock through `WorkDraftTransitions.written/retry/failed`. Wired from `RunEventConsumer`
  beside `WorkItemRunBridge`.
- Modify: `WorkDraftTransitions.java` and the contract decisions (`WorkDraftLifecycle`):
  `written(item, artifact)` → `DRAFT_ARTIFACT_WRITTEN`; mode `approve` → result gate (`waiting_approval /
  specification_written`, gate artifact = sha256); mode `auto` → `DRAFT_APPROVED` and the next phase;
  `retryRequested(item)` → a new attempt (`DRAFT_RETRY_REQUESTED`, effect row `retry_of` set, validation error
  stored encrypted); a second invalid → `awaiting_input / spec_invalid`; `RunFailed` → `awaiting_input /
  draft_run_failed`.
- Modify: `WorkItemTransitions.answer`: a gate on `spec`/`plan` whose item has a draft with that phase's artifact is a
  result gate: approve → `WorkDraftLifecycle.approved` (next phase), reject → `stopped / gate_rejected`. Keep the
  change to the two lines that detect it, as `verifyResult` is detected; the decision body is in the contract.
- Modify: `WorkPhaseCapability` (`WorkDraftTransport.available()` = the run transport's availability).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftComments.java`: on acceptance,
  enqueue one tracker comment through `WorkSourceEffects.enqueue(…, COMMENT, text)`: "Specification written by the
  factory", digest, the neutralised specification in a fenced block, "Approve or revise it on the dashboard".
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftResource.java`:
  `GET /work-items/{id}/draft` → `{specification:{text,sha256,commentUrl}, plan:null, revisions}` (decrypted, only
  for operators who can read the item, like the preparation resource).
- Create: `spire-ui/src/components/work-items/SpecificationView.tsx` (+ `workDraftApi.ts`): Outcome and criteria
  rendered as text, never as HTML; digest; the ticket comment link. Shown in the Spec journey step and the decision
  panel's evidence for a spec gate.
- Modify: `spire-ui/src/components/work-items/workReasons.ts` (`spec_invalid`, `draft_run_failed`,
  `specification_written`, `draft_binding_mismatch`).
- Modify: `docs/CONTRACT.md` (milestones, reasons, the result gate on spec), `docs/DATA-MODEL.md` (what the
  effect row stores and its AADs).
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/SpecificationRulesTest.java` (new, plain),
  `UntrustedTextTest.java` (new, plain), `WorkDraftPromptsTest.java` (new, plain),
  `WorkSpecRunIT.java` (new, service, `GeneratedPreparationProfile`, a fake run transport that records the command
  and a fake tracker like `WorkPreparedFixture`).
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkDraftLifecycleTest.java` (extend).
- Test: `spire-ui/src/components/work-items/SpecificationView.test.tsx` (new), `DecisionPanel.test.tsx` (extend).

**Interfaces — Produces:** `WorkDraftLifecycle.written/retryRequested/runFailed/approved`, `SpecificationRules.check(String)
→ Optional<String>`, `UntrustedText`, `WorkDraftPrompts.specification(Ticket, Optional<String> retryError,
Optional<String> note)`, `GET /work-items/{id}/draft`. Consumes slice 4's command/result and slice 5's effect.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `SpecificationRulesTest.allFiveHeadingsAndOneCriterionPass` | a `TEST-` spec with the headings and `- TEST-criterion` → empty | |
| `…aMissingHeadingFailsNamingIt` (parameterised over the five) | missing `## Context` → "missing heading: Context" | heading not checked |
| `…anEmptyCriteriaListFails` | criteria heading with prose only → "acceptance criteria need at least one list item" | |
| `…openQuestionsMaySayNone` | `## Open questions\nNone` → passes | |
| `…aSpecificationOver16KiBFails` | 16 KiB + 1 → fails | cap missing |
| `UntrustedTextTest.theFenceOutgrowsAnyBacktickRunInside` | text with ```` ```` ```` → fence of 5 | fixed fence lets text close it |
| `…controlCharactersAreStripped` | `\u0007` and `\u001b[31m` removed, `\n`/`\t` kept | |
| `…mentionsAndHtmlAreNeutralised` | `@TEST-user <script>` → `@​TEST-user &lt;script>` | |
| `WorkDraftPromptsTest.theTicketIsFencedAsData` | prompt contains the data heading and the ticket inside the fence | ticket inlined raw |
| `…anEmptyBodyIsAllowed` | title only → prompt built | empty body refused (composer rule copied) |
| `…aRetryCarriesTheValidationError` | retry → error sentence in a fenced block | |
| `WorkDraftLifecycleTest.specApproveOpensAResultGateOnTheArtifact` | `written` under approve → `waiting_approval / specification_written`, gate artifact = sha256, phase spec | permission gate or binding used |
| `…specAutoMovesToPlan` | under auto → `DRAFT_APPROVED`, phase plan, draft approved | |
| `WorkSpecRunIT.theSpecRunIsDispatchedReadOnly` | effect `pending` → one `ExecuteWorkDraftRun(SPEC)`, base commit = forge head, `factory_run.kind='SPEC'` | dispatcher missing |
| `…anUncertainDraftDispatchIsNeverResent` | transport throws → row `uncertain`; next drain sends nothing | resend on uncertain |
| `…aValidArtifactIsStoredEncryptedAndGated` | `RunWorkArtifact` with a valid spec → `work_item_artifact` SPEC row, body ≠ plaintext bytes, item `waiting_approval / specification_written` | not stored or stored plain |
| `…aMissingHeadingRetriesOnceThenWaits` | two invalid artifacts → two runs, then `spec / awaiting_input / spec_invalid` | no retry, or more than one |
| `…theRetryPromptNamesTheError` | second command's prompt contains "missing heading" | error dropped |
| `…aFailedSpecRunWaitsNotFails` | `RunFailed` → `awaiting_input / draft_run_failed`, not `failed` | PHASE_FAILED reused |
| `…aForeignArtifactIsRefused` | result whose provisional digest differs → `draft_binding_mismatch`, item unchanged | binding not checked |
| `…theSpecificationIsPostedNeutralised` | one tracker outbox COMMENT with the head, digest, `@​` | comment missing or raw |
| `…approvingTheResultGateMovesToPlan` | dashboard approve → phase plan, `plan_capability_unavailable` (until slice 7) | approve re-enters spec and reruns |
| `…rejectingStops` | reject → `stopped / gate_rejected` | |
| `…theSpecRunIsChargedAsSpec` | `llm_charge.kind='SPEC'`, item cost incremented, `runs` unchanged | counted as a build run |
| `SpecificationView.test.tsx › renders the criteria as text` | a `<b>` in the spec shows literally | `dangerouslySetInnerHTML` |
| `› shows the digest and the ticket comment link` | | |
| `DecisionPanel.test.tsx › a spec gate shows the specification as evidence` | gate on spec with a draft → `SpecificationView` | old binding evidence |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for the specification run`, then
  `Write the specification with a read-only run and gate it`.

**Mutation guards:** each `SpecificationRules` check; the fence length computation; the control-strip range;
`neutralise`'s two replacements; the binding comparison in `WorkDraftResults`; the retry count condition (`== 0`);
the result-gate detection in `answer`; the uncertain-row guard in the dispatcher; the encryption call on store.

---

### Slice 7: Revise, the plan run, plan schema v2, binding 6 and registration

**Goal:** an operator can revise a spec or plan with a note (dashboard only, ≤ 3 per phase); the plan run writes a
`schemaVersion: 2` plan that is validated (fields, caps, digest, prompt budget, one retry); an approved plan
registers a binding-6 preparation and enters build. A multi-step plan cannot build yet (the build prompt still
takes one step): until slice 9, the registration refuses N > 1 with `plan_steps_unavailable` so nothing builds the
wrong thing; the flag keeps it dark either way.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkPreparation.java` (`STEPS_BINDING = 6`; the
  upper bound check becomes `≤ STEPS_BINDING`; `binding()` hashes exactly what version 5 hashes, A14; javadoc says
  it is a validation-rule version).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkArtifacts.java`: `Evidence` gains
  `List<Step> steps` (`record Step(String id, String title, String instruction, String doneWhen)`); the plan rules
  move into `PlanRules` (new) so the file stays small: v1 under bindings ≤ 5 keeps exactly one step and today's
  details; v2 only under binding 6, 1 ≤ N ≤ `maxStepsPerPlan`, unique ids, every field non-blank, caps (A13),
  digest equals the specification. `Evidence.instruction()` stays for v1 callers; v2 sets it to the first step's
  instruction until slice 9 replaces the consumer.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/PlanRules.java` (pure; takes the parsed
  root, the binding version, the spec digest, `maxStepsPerPlan`).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/StepPromptBudget.java`: given the spec and
  the steps, computes the worst-case step prompt (spec + numbered titles + the largest current step + 12 KiB of
  summaries + 8 KiB of verify section) and refuses over 48 KiB, naming which part (A8).
- Modify: `WorkDraftPrompts.java` (plan prompt: the approved specification fenced, the verify commands,
  `maxStepsPerPlan`, the JSON shape, "steps are vertical slices: each leaves the repository working and testable").
- Modify: `WorkDraftResults.java` (PLAN kind: parse → `PlanRules` → `StepPromptBudget` → store `PLAN` artifact →
  `written`; comment "Plan written by the factory" with numbered step titles, neutralised).
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkDraftRegistration.java`: on plan
  approval (or plan auto), composes `WorkPreparation` (STORED spec and plan, `draft.baseCommit()`, the build setup's
  harness/model/effort/payWith/verify, `STEPS_BINDING`, registeredBy `system:draft@<setup revision>`), re-checks
  the setup revision under the lock, and appends `DRAFT_REGISTERED` with phase `build` through
  `WorkDraftLifecycle.registered(item, preparation)`; then `enterPrepared`. It counts only build-or-later attempts
  (A4), so the spec/plan attempts of this generation do not refuse it.
- Modify: `WorkDraftLifecycle` (`revised(item, phase, note)` → `DRAFT_REVISED`, gate `REJECTED` with the note,
  re-entry with reason `revision_requested`; refuses the fourth; `registered`).
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkGateResource.java`
  (`POST /api/approvals/{id}/revise`, `spire-admin`), routed to `WorkDraftTransitions.revise`; `ResolveGate` is untouched, so the
  tracker and PR-review channels cannot revise (A6).
- Modify: the effect row on a revision stores the note encrypted (`note`), and `WorkDraftPrompts` fences it.
- Modify: `WorkDraftResource` (plan steps; revision counts).
- Create: `spire-ui/src/components/work-items/PlanView.tsx` (numbered titles and done-when lines); modify
  `DecisionPanel.tsx`/`DecisionEvidence.tsx` (plan evidence; a "Revise with a note" action with a required note,
  hidden after 3 revisions, with the count shown).
- Modify: `workReasons.ts` (`plan_invalid`, `plan_written`, `revision_requested`, `revision_limit_reached`,
  `revise_unavailable`, `plan_steps_unavailable`).
- Modify: `docs/factory/PREPARED-TASKS.md` (binding 6 beside 5, schema v2), `docs/CONTRACT.md`.
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkPreparationBindingVersionTest.java` (extend),
  `WorkDraftLifecycleTest.java` (extend).
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/PlanRulesTest.java` (new, plain),
  `StepPromptBudgetTest.java` (new, plain), `WorkArtifactsTest.java` (extend), `WorkPlanRunIT.java` (new, service).
- Test: `spire-ui/src/components/work-items/PlanView.test.tsx` (new), `DecisionPanel.test.tsx` (extend).

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkPreparationBindingVersionTest.bindingSixHashesWhatFiveHashes` | same fields at 5 and 6 → equal `binding()` | v6 adds a field |
| `…bindingSixIsAccepted` / `…bindingSevenIsRefused` | constructor bounds | bound not raised, or raised past 6 |
| `PlanRulesTest.versionOneKeepsExactlyOneStep` | v1 with two steps under binding 5 → `plan_step_count` | v1 relaxed |
| `…versionTwoNeedsBindingSix` | v2 under binding 5 → `plan_schema_version` | |
| `…bindingSixAcceptsNSteps` | 3 steps → evidence with 3 steps in order | |
| `…duplicateIdsAreRefused` | `TEST-s1` twice → `plan_step_ids` | |
| `…moreThanMaxStepsIsRefused` | N = max + 1 → `plan_step_count` | bound off by one |
| `…eachFieldCapApplies` (parameterised: title 121, instruction 4001, doneWhen 1001) | → `plan_step_fields` | a cap missing |
| `…theDigestMustBeTheApprovedSpecification` | other digest → `plan_specification_mismatch` | |
| `StepPromptBudgetTest.aPlanOverThePromptBudgetIsInvalid` | 16 KiB spec + titles over 4 KiB → refused naming "plan titles" | budget not checked |
| `…theWorstStepDecides` | one 4 KiB step among small ones → computed with it | first step used |
| `WorkArtifactsTest.bindingSixAcceptsNSteps` (service) | a stored v2 plan observes to 3 steps | |
| `WorkDraftLifecycleTest.reviseReentersWithTheNote` | revise spec → gate REJECTED with note, `DRAFT_REVISED`, revisions 1, phase spec started again, reason `revision_requested` | |
| `…revisingTheSpecificationDiscardsThePlan` | draft with plan → revise spec → plan null | plan kept |
| `…theFourthRevisionIsRefused` | revisions 3 → `IllegalStateException` (resource maps 409 `revision_limit_reached`) | |
| `WorkPlanRunIT.aValidPlanIsGatedOnItsDigest` | plan artifact → `waiting_approval / plan_written`, gate artifact = plan sha256, comment with numbered titles | |
| `…anInvalidPlanRetriesOnceThenWaits` | → `plan / awaiting_input / plan_invalid` after two runs | |
| `…approvingTheOneStepPlanRegistersAndBuilds` | approve → `DRAFT_REGISTERED`, preparation binding 6 with STORED artifacts and `draft.baseCommit()`, build `PHASE_STARTED` (build auto) | registration refused by the spec/plan attempts (A4) |
| `…aMultiStepPlanWaitsUntilStepsExist` | 3 steps approved → `awaiting_input / plan_steps_unavailable` (removed in slice 9) | builds step 1 only and delivers |
| `…onlyTheDashboardMayRevise` | `ResolveGate` from TRACKER with a note → plain reject, no `DRAFT_REVISED` | revise reachable through `answer` |
| `…theFourthRevisionIsRefused` | 409 `revision_limit_reached`, gate still OPEN | |
| `…aTicketEditAfterApprovalChangesNothing` | edit the fake ticket after spec approval → plan prompt carries the stored spec | ticket re-read |
| `…aSetupChangeBeforeRegistrationRefuses` | change the build setup between approval and registration → `build_defaults_changed`, no preparation | revision not re-checked under the lock |
| `PlanView.test.tsx › lists numbered titles and done-when lines` | | |
| `DecisionPanel.test.tsx › Revise needs a note and hides after three` | button disabled with an empty note; count 3 → no Revise | |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for revisions and the plan run`, then
  `Write the plan with a read-only run and register it`.

**Mutation guards:** the binding upper bound; v2-needs-6; each `PlanRules` check and cap; the budget comparison;
revision bound; spec revision clearing the plan; the build-or-later attempt filter in registration; the setup
revision re-check; the `plan_steps_unavailable` guard (removed in slice 9, mutation-checked here).

---

### Slice 8: step summaries — read, carried, stored

**Goal:** a build run's ready result carries the agent's step summary (capped, scrubbed); the orchestrator stores it
encrypted in `work_step_summary` (or `summary_missing`) and the dashboard can read it. Prompts do not use it yet.

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/event/RunResult.java`: `RunWorkReady` gains
  `stepSummary` (nullable `StepSummary`) through `withStepSummary`; every existing constructor call passes `null`.
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/StepSummary.java`
  `record StepSummary(List<String> decisions, List<String> alternativesRejected, List<String> deviations)` with the
  caps (each list ≤ 20, each item ≤ 500 after control stripping); `static Optional<StepSummary> parse(String json)`
  stays out of the contract (no databind there): parsing lives in the run worker.
- Modify: `ContractSchemaSnapshotTest` nested list + `contract-schema.txt`.
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/StepSummaries.java`: parse the `step_summary` line's
  JSON with caps (items over 500 cut at a code point boundary with "…", lists over 20 truncated, unknown fields
  ignored, a non-object → empty), scrubbed with `failures.scrubFor(command)`.
- Modify: `RunLauncher.java` (held branch: `RunWorkReady….withStepSummary(StepSummaries.from(outcome, scrub))`).
- Create: `spire-orchestrator/src/main/resources/db/migration/V98__work_step_summary.sql`:
  `work_step_summary(work_item_id, generation, step_index INT, run_id, summary BYTEA NULL, state TEXT CHECK (state IN
  ('recorded','summary_missing')), created_at, PRIMARY KEY (work_item_id, generation, step_index, run_id))`.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkStepSummaries.java`: `record(c, …)`
  encrypting under `work-step-summary:<item>:<generation>:<step>:<run>`; `list(item, generation)` decrypted, newest
  run per step. Called from `WorkItemRunBridge.finishBuild` on readiness, with step index 0 until slice 9.
- Modify: `WorkDraftResource` → `GET /work-items/{id}/steps` (summaries per step; `summary_missing` as a state).
- Modify: `docs/DATA-MODEL.md` (V98), `docs/CONTRACT.md` (`RunWorkReady.stepSummary`).
- Test: `spire-contract/…/StepSummaryTest.java` (new), `ContractSchemaSnapshotTest`, `WorkReadyProtocolTest`.
- Test: `spire-run-worker/…/StepSummariesTest.java` (new), `RunLauncherTest.java`.
- Test: `spire-orchestrator/…/work/WorkStepSummariesIT.java` (new, service).

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `StepSummaryTest.listsAndItemsAreCapped` | 21 items or a 501-char item → refused | caps missing |
| `RunWorkReadyTest.withStepSummaryEnumeratesEveryComponent` | wither keeps run, work, head, paths, usage, wall | component dropped |
| `ContractSchemaSnapshotTest` | `RunWorkReady` shows `stepSummary`; nested `StepSummary` | |
| `StepSummariesTest.aSummaryIsCappedAndScrubbed` | 10,000 items, one with `TEST-key` → 20 items, ≤ 500 chars, no `TEST-key` | |
| `…aNonObjectIsMissing` | `"[1]"` → empty | throws |
| `…unknownFieldsAreIgnored` | extra key → parsed | strict mapper |
| `RunLauncherTest.theReadyResultCarriesTheSummary` | held run with `step_summary` line → `ready.stepSummary()` present | not attached |
| `…noSummaryLineMeansNull` | → `stepSummary()==null` | empty summary invented |
| `WorkStepSummariesIT.theSummaryIsStoredEncrypted` | ready with a summary → row `recorded`, bytes ≠ plaintext, `list` decrypts | stored plain |
| `…aMissingSummaryIsRecordedAsMissing` | ready without → row `summary_missing` | no row, so the screen cannot say so |
| `…aRedeliveredReadyRecordsOnce` | same ready twice → one row | no conflict handling |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for step summaries`, then `Carry and store each build's step summary`.

**Mutation guards:** each cap; the scrub call; the `summary_missing` branch; the encryption call; the primary key's
conflict clause.

---

### Slice 9: the step cursor, step prompts and the checkpoint chain

**Goal:** a binding-6 plan of N steps builds step by step. The decider keeps a cursor; each step's prompt carries
the spec, the numbered plan, the current step and every earlier summary; step k+1 starts from step k's checkpoint
on the same chain; a missing checkpoint fails the step instead of rebasing; a head that does not extend the start
is refused twice; steps are counted on completion.

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/WorkSteps.java`:
  ```java
  public record WorkSteps(int total, int index, List<StepResult> completed) {
      public record StepResult(int index, String runId, String head, UUID verificationAttempt,
                               WorkVerification.Outcome verification, boolean empty) {}
      public static WorkSteps of(int total);
      public WorkSteps completedWith(StepResult result);   // index + 1
      public Optional<StepResult> previous();              // last completed
      public Optional<StepResult> lastNonEmpty();
      public boolean done();
  }
  ```
  `index` in `[0,total]`, `completed.size()==index`, indices consecutive, total in `[1,50]`.
- Modify: `WorkItemEvent.java`: component `steps` after `draft` with wither `stepped(WorkSteps)`; every wither and
  shorter constructor carries/passes it (A17).
- Modify: `WorkItemLifecycle`/`WorkDraftLifecycle` → new `WorkStepLifecycle` (contract): `planRegistered` sets
  `WorkSteps.of(N)` when binding 6; `stepPassed(item)` after a PASSED verify for step k < N-1 → `STEP_COMPLETED`,
  `progress.steps + 1`, phase `build` again (not `deliver`) with the execution kept for the chain, then `enter`;
  for k = N-1 → the existing path to `deliver`. The hard-coded `nextPhase` switch is untouched: the advance path in
  `WorkItemTransitions.advance` asks `WorkStepLifecycle.next(item)` first when the item has steps (two lines).
- Modify: `WorkProgress.java`: `start` stops counting `steps` (A11); new `completeStep()` increments it;
  `limitReached`/`within` compare `steps` with `maxStepsPerPlan` only for a plan step start, and
  `startKeepingExecution` (land fix) skips the steps check (A11). Existing tests that pinned "start counts a step"
  change with a comment saying why.
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/StepPrompts.java`: the build prompt for
  step k: the spec (fenced), "The plan" numbered titles (fenced), "Now do step k: <title> — <instruction>. Done when:
  <doneWhen>" (fenced), "Decisions recorded by earlier steps" oldest first, "Step j recorded no decisions" for a
  missing one, cut to 12 KiB keeping the newest in full (A8), then the verify-failure section when retrying.
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/WorkRunAssembly.java`: when the item
  has steps, the prompt comes from `StepPrompts` instead of the single-step text; step k > 0 (or a retry) uses
  `fromCheckpoint(previous step's runId, head)`, or the failed checkpoint for a retry, and the command is marked
  `checkpointRequired`; `QueuedRun…atStep(k)`; branch `spire/work-<attempt>` per step (A15). Keep the method
  readable: move the step part into `WorkStepAssembly`.
- Modify: `spire-contract/…/command/RunCommand.java`: `ExecuteWorkRun` gains `checkpointRequired` through
  `requiringCheckpoint()`; true only with `startFromRunId` set.
- Modify: `spire-run-worker/…/WorkRunWorker.java` (`readableStart`: `checkpointRequired` and the unit is gone →
  `RunFailed(CHECKPOINT_MISSING)`, no base fallback, A2).
- Modify: `spire-publisher/…/PublishCycle.java` + `PublicationPolicy` (HELD: optional `SPIRE_START_HEAD`; a checkpoint
  sha that does not descend from it → `failed("START_NOT_DESCENDED")`, no `checkpoint` line); `RunUnitBuilder`
  passes `SPIRE_START_HEAD = startFromHead` to the held publisher.
- Modify: `RunFailureCause` (`CHECKPOINT_MISSING(false, true)` — it may have spent nothing, but the cause is
  terminal for the step — and `START_NOT_DESCENDED(false, true)`).
- Modify: `RunResult.RunWorkReady`: `startFromHead` (nullable) through `withStartFromHead`; set by `RunLauncher` from
  the command.
- Modify: `WorkItemRunBridge`/`validExecution` (via `WorkStepTransitions`): a step k > 0 ready head must not equal
  a refused chain — the orchestrator refuses a ready whose `startFromHead` is not the previous step's head
  (`phase_evidence_mismatch`), and a `CHECKPOINT_MISSING` failure of a plan step becomes an UNVERIFIED
  `checkpoint_missing` verify result on the previous step's head (decision 7), opening today's verify gate.
- Create: `V99__work_item_step_projection.sql`: `work_item.step_index INT`, `work_item.step_total INT` (projection
  columns, A17); `WorkDraftProjection` writes them.
- Remove: slice 7's `plan_steps_unavailable` guard and its copy.
- Modify: `WorkStepSummaries` callers pass the real step index.
- Modify: `docs/CONTRACT.md` (component, milestones, causes, `checkpointRequired`, `startFromHead`),
  `docs/DATA-MODEL.md` (V99), `docs/factory/RUN-TOPOLOGY.md` (step chain, no base fallback).
- Test: `spire-contract/…/WorkStepsTest.java` (new), `WorkStepLifecycleTest.java` (new), `WorkProgressTest.java`,
  `ExecuteWorkRunTest`, snapshot.
- Test: `spire-orchestrator/…/work/StepPromptsTest.java` (new, plain), `WorkStepsIT.java` (new, service: a fake run
  transport and verify transport answering PASSED).
- Test: `spire-run-worker/…/WorkRunWorkerTest.java`, `RunUnitBuilderTest.java`; `spire-publisher/…/HeldPublicationTest.java`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkStepsTest.indicesStayConsecutive` | a result with index ≠ size → refused | |
| `WorkStepLifecycleTest.aPassedStepGoesBackToBuild` | step 0 of 3 PASSED → `STEP_COMPLETED`, phase build, index 1, steps +1 | goes to deliver |
| `…theLastPassedStepGoesToDeliver` | step 2 of 3 → deliver | loops |
| `WorkProgressTest.stepsCountCompletionsNotStarts` | `start("build")` keeps `steps`; `completeStep()` +1 | start still counts |
| `…draftRunsDoNotCountTowardMaxRuns` | `start("spec")`/`("plan")` keep `runs` | |
| `…aLandFixAfterAPlanIsNotAStep` | `steps == maxStepsPerPlan`, `startKeepingExecution("build")` allowed by `within` | steps check applied to a fix |
| `StepPromptsTest.stepThreesPromptCarriesStepOnesDecision` | summaries of steps 1 and 2, step 3 prompt contains step 1's `TEST-decision` | earlier summaries omitted |
| `…summariesAreCutOldestFirst` | 20 KiB of summaries → ≤ 12 KiB, newest in full, oldest cut | cut newest |
| `…aMissingSummaryIsSaidPlainly` | step 1 missing → "Step 1 recorded no decisions" | silently dropped |
| `…everyQuotedPartIsFenced` | spec, plan, step and summaries inside data fences | raw |
| `ExecuteWorkRunTest.requiringACheckpointNeedsAStart` | `requiringCheckpoint()` without a start → refused | |
| `WorkRunWorkerTest.aPlanStepNeverFallsBackToTheBase` | `checkpointRequired`, previous unit gone → `RunFailed(CHECKPOINT_MISSING)`, no launch | base fallback taken |
| `…aRetryOutsideAPlanStillFallsBack` | not required → today's `fromBaseInstead` | rule applied to every retry |
| `HeldPublicationTest.aHeadThatDoesNotDescendIsRefused` | `SPIRE_START_HEAD=X`, bundle on an unrelated root → `START_NOT_DESCENDED`, no checkpoint | ancestry not checked |
| `…aHeadOnTheChainIsACheckpoint` | descendant → checkpoint | |
| `RunUnitBuilderTest.theHeldPublisherIsToldTheStartHead` | `fromCheckpoint` → publisher env `SPIRE_START_HEAD` | |
| `WorkStepsIT.threeStepsBuildOnOneChain` | three passes → three build runs, steps 2 and 3 `startFromRunId` = previous run, heads as reported, then deliver of step 3's run | chaining missing |
| `…eachStepRunRecordsItsIndex` | `factory_run.step_index` 0,1,2 | |
| `…theOrchestratorRefusesAHeadOffTheChain` | step 2 ready with `startFromHead` ≠ step 1 head → `phase_evidence_mismatch` | |
| `…aMissingCheckpointOpensTheVerifyGate` | `RunFailed(CHECKPOINT_MISSING)` at step 2 → `verify / waiting_approval / verify_unverified`, verification reason `checkpoint_missing` | item failed |
| `…retryingAStepKeepsEarlierSummariesAndCountsOnce` | step 2 FAILED → Retry build → prompt still has step 1's decision, starts from step 2's failed checkpoint, `steps` 1 after step 1 and 2 after step 2 passes | |
| `…aStepRunCountsTowardMaxRuns` | `maxRunsPerItem = 2`, 3 steps → `policy_cap_reached` before step 3 | step runs uncounted |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for building a plan step by step`, then `Build a plan step by step on one chain`.

**Mutation guards:** `WorkSteps` invariants; the build-vs-deliver branch in `stepPassed`; `start` not counting steps;
`completeStep`; the land-fix steps exemption; the summary cut direction; the `checkpointRequired` branch in
`readableStart`; the publisher ancestry check; the orchestrator's `startFromHead` comparison; the CHECKPOINT_MISSING
→ verify gate mapping.

---

### Slice 10: empty steps, delivery from the last non-empty step, and the steps view

**Goal:** a step that commits nothing completes as empty (publisher signal for step 1, orchestrator detection for
later steps), skips verify, and moves on; all steps empty stops at `plan_produced_no_change`; deliver uses the last
non-empty step and the pull request body lists the steps and their summaries; the dashboard shows one row per step.

**Files:**
- Modify: `spire-publisher/…/PublisherMain.java`/`PublishCycle.java` (HELD: `DONE` with zero bundles and zero new
  commits → `checkpoint` `{head: <base or start head>, empty: true}`; a later bundle can never follow `DONE`).
- Modify: `spire-run-worker/…/PublisherOutcome.java` (`checkpointEmpty()`), `RunLauncher.java` (held branch accepts
  an empty checkpoint as ready).
- Modify: `RunResult.RunWorkReady`: `empty` boolean through `withEmpty`; refused unless `head` equals
  `startFromHead` or (no start) the base the orchestrator knows — the contract checks `empty → startFromHead == null
  || head.equals(startFromHead)`.
- Modify: `WorkStepLifecycle`: `stepEmpty(item, result)` → `STEP_EMPTY`, verify skipped, `completeStep`, next step
  or deliver; the orchestrator marks step k > 0 empty when `ready.head == step start head` (A3); all steps empty →
  `build / awaiting_input / plan_produced_no_change`, milestone `PLAN_PRODUCED_NO_CHANGE`, Stop or Readmit offered.
  An empty step 1's next step starts from the base (no `fromCheckpoint`); an empty step k > 1 keeps the previous
  checkpoint (A3).
- Modify: deliver: the execution given to `deliver` is `steps.lastNonEmpty()`'s run and head (A10); since each step's
  handoff carries the whole chain from the base, that run's publication pushes every step's commits on its own
  `spire/work-<attempt>` branch (A15).
- Modify: `spire-orchestrator/…/factory/FactoryPullRequestBody.java` (steps section: numbered titles, "empty" where
  so, and each summary's decisions, neutralised and capped so the body stays under the forge limit).
- Modify: `RunEventProjection`/run detail resource: "Plan step 2 of 4" from `step_index` and the item's
  `step_total`.
- Create: `spire-ui/src/components/work-items/StepRows.tsx`: per step title, state (waiting, building, verifying,
  passed, empty, failed), commit, summary expandable, "No decisions recorded" plainly; used in the Build journey
  step of `WorkItemSteps.tsx`. Run detail shows "Plan step 2 of 4".
- Modify: `workReasons.ts` (`plan_produced_no_change`), `workJourney.ts` (next action for it: Stop or Readmit).
- Modify: `docs/CONTRACT.md` (`empty`, milestones), `docs/factory/AGENT-IMAGE-CONTRACT.md` only if the entrypoint
  changes (it should not: A3 says the entrypoint reports nothing).
- Test: `spire-publisher/…/HeldPublicationTest.java`, `spire-run-worker/…/PublisherOutcomeTest.java`,
  `RunLauncherTest.java`, `spire-contract/…/WorkReadyProtocolTest.java`, snapshot, `WorkStepLifecycleTest.java`,
  `spire-orchestrator/…/work/WorkEmptyStepsIT.java` (new, service), `…/factory/FactoryPullRequestBodyTest.java`,
  `spire-ui/…/StepRows.test.tsx` (new), `WorkItemSteps.test.tsx`.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `HeldPublicationTest.theHeldPublisherSignalsAnEmptyFirstStep` | no bundle, `DONE` → `checkpoint` head = base, `empty: true`, exit 0 | today's "no observed checkpoint" |
| `…aBundleIsNeverEmpty` | one bundle → `empty` absent | |
| `RunLauncherTest.anEmptyCheckpointIsReady` | → `RunWorkReady(empty=true)` | PUBLISHER_FAILED |
| `RunWorkReadyTest.anEmptyReadyMustStandOnItsStart` | `empty`, head ≠ start → refused | guard missing |
| `WorkStepLifecycleTest.anIdleLaterStepIsEmpty` | step 1 ready head == step 0 head → `STEP_EMPTY`, no verify | verified again |
| `…anEmptyStepSkipsVerify` | no verify attempt started | |
| `WorkEmptyStepsIT.everyStepEmptyDeliversNothing` | 3 empty → `build / awaiting_input / plan_produced_no_change`, no delivery row | delivers the base |
| `…deliverUsesTheLastNonEmptyStep` | steps: change, change, empty → delivery effect `run_id` = step 2's run | last step's run |
| `…anEmptyFirstStepStartsTheNextFromTheBase` | step 1 empty → step 2 command has no `startFromRunId` | chained to a checkpoint that has no bundle |
| `…anEmptyLaterStepKeepsThePreviousCheckpoint` | step 2 empty → step 3 starts from step 1's run | |
| `FactoryPullRequestBodyTest.theBodyListsTheStepsAndSummaries` | numbered titles, "empty", decisions; `@` neutralised | steps missing |
| `StepRows.test.tsx › shows each step's state and an expandable summary` | | |
| `› says plainly when a step recorded no decisions` | | |
| `WorkItemSteps.test.tsx › the build step lists plan steps` | | |

- [ ] RED; push; confirm. GREEN; push; green.
- [ ] Commits: `Add failing tests for empty steps and step delivery`, then
  `Complete empty steps and deliver the last change`.

**Mutation guards:** the publisher's zero-bundle condition; the `empty` contract guard; the orchestrator's
`head == start head` comparison; the verify skip; the all-empty condition; `lastNonEmpty`; empty step 1 → no
checkpoint start; the PR body neutralisation.

---

### Slice 11: readmit reuse, Regenerate, the flag removed, and the documents

**Goal:** readmit reuses an approved draft and preparation (spending nothing on spec and plan); the readmit dialog
offers "Regenerate specification and plan"; the feature flag is deleted; every document the spec names is updated.

**Files:**
- Modify: `WorkItemEvent.readmit()` keeps `preparation`, `draft` and resets `steps` to `WorkSteps.of(total)` (A7);
  an unapproved draft is dropped (nothing approved to reuse).
- Create: `WorkDraftLifecycle.regenerated(item)` → new generation with `draft = WorkDraft.admitted()`,
  `preparation = null`, reason `specification_to_write`; refused `regenerate_unavailable` where readmit is refused
  (land pending, landed, merge unconfirmed) and for a composed item.
- Modify: `WorkItemResource` readmit endpoint accepts `{regenerate: boolean}`; `WorkItemActions.tsx` readmit dialog
  gains the checkbox for generated items only.
- Delete: `GeneratedPreparation.java` and `GeneratedPreparationProfile`; every caller reads the repository setting
  alone; tests that used the profile run without it; `BuildDefaultsTest.generatedIsRefusedWhileDark` is removed
  with a note in the commit body.
- Docs:
  - `docs/DECISIONS.md`: **ADR-049** — specification and plan are read-only harness runs on the build's harness,
    model and payment; steps build on one chain of held checkpoints; summaries and the artifact travel through the
    image contract; `kind` + `step_index` answer #115's phase column (A18); it **supersedes** FR-F18's mechanism
    sentence (A19). Status lines of ADR-046, ADR-047, ADR-048 say what live proof was recorded (A21; the
    acceptance records `M4-VERIFY-ACCEPTANCE.md`, `GITHUB-REVIEWS-ACCEPTANCE.md`, `LAND-ACCEPTANCE.md`).
  - `docs/factory/PRD.md` (FR-F18 mechanism superseded by ADR-049 at lines 167–168; FR-F19, F20, F31 notes),
    `docs/factory/AUTONOMY.md` (§1 lines 20–21 superseded; the spec/plan rows; "no gate between passing steps" as the
    author's call, A22), `docs/CONTRACT.md` (final pass), `docs/DATA-MODEL.md` (final pass),
    `docs/factory/PREPARED-TASKS.md`, `docs/UNVERIFIED.md` (new entry: spec/plan/steps proven against fakes only
    until the live proof; entry for the publisher's Docker log carrying the scrubbed artifact),
    `docs/factory/ROADMAP.md` (M4 slice 3 built, live acceptance pending), `docs/HISTORY.md` (milestone entry),
    `CLAUDE.md` Status snapshot (rewritten, not appended).
- Test: `spire-contract/…/WorkItemEventTest.java` (readmit), `WorkDraftLifecycleTest.java`,
  `spire-orchestrator/…/work/WorkDraftReadmitIT.java` (new, service), `spire-ui/…/WorkItemActions` tests.

**Failing tests first:**

| Test | Asserts | Fails when |
|---|---|---|
| `WorkItemEventTest.readmitKeepsAnApprovedDraftAndResetsSteps` | approved draft + preparation + steps 2/3 → kept, steps 0/3 | steps kept, or draft dropped |
| `…readmitDropsAnUnapprovedDraft` | draft with only a spec → `admitted()` | half-draft kept |
| `WorkDraftReadmitIT.readmitReusesTheApprovedDraft` | delivered generated item, readmit → no `SPEC`/`PLAN` run dispatched, build step 1 starts | spec and plan rerun |
| `…regenerateWritesANewDraft` | readmit with regenerate → `specification_to_write`, one SPEC run | old draft reused |
| `…regenerateIsRefusedWhereReadmitIs` | land pending → 409 `readmission_land_pending` | regenerate bypasses readmit refusals |
| `…aComposedItemCannotRegenerate` | → 409 `regenerate_unavailable` | |
| `WorkItemActions test › the readmit dialog offers Regenerate only for generated items` | | |
| `ContractAndCheckerAgreeTest` (existing) | still green after the docs pass | doc and clauses drifted |

- [ ] RED; push; confirm. GREEN (code + flag removal + docs); push; green.
- [ ] Commits: `Add failing tests for readmit and regenerate`, then
  `Reuse an approved draft on readmit and document generated work`.

**Mutation guards:** readmit's approved-draft condition; the steps reset; regenerate's refusal reuse; the composed
refusal.

---

### After slice 11: review and live proof

- [ ] Run the review skill over the PR diff (security, rules, QA lenses); fix findings through the same RED/GREEN
  loop; record mutation results in `.claude/reviews/global/factory-m4-spec-plan-mutations.md`.
- [ ] Live proof on `spire-test` (spec §8), recorded in `docs/factory/M4-SPEC-PLAN-ACCEPTANCE.md`: a title-only
  ticket with one vague sentence → specification with criteria posted → plan of ≥ 3 steps posted → each step builds
  and verifies → one pull request → review → land; step 3's recorded prompt contains step 1's decision; a step told
  to change nothing completes as empty; Revise with a note changes the specification. Then move the PR out of draft.
