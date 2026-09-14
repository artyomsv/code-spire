# M3 acceptance record

All seven acceptance criteria were independently verified before the final cleanup slice.
Slice 9 was accepted without findings in round 16. The final slice retains these decisions;
the pull request stays draft until the operator's final review. This record distinguishes
accepted control-plane evidence, real local execution, and live-forge evidence.

## Seven verified criteria

| # | Accepted outcome and discriminating proof | Proving slice / review | Evidence |
|---|---|---|---|
| 1 | The same prepared task has three visible journeys: suggest stops before BUILD; assisted requires a PLAN answer before one build; autonomous admits one build immediately. Tests compare phases, gates, decisions and run counts rather than profile names. Artifact history stores references, and manual acceptance cannot invent PHASE_COMPLETED. | 8a / round 12 | [Journey evidence](../../.claude/reviews/global/factory-m3-slice8a.md), [Java journey](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemJourneyIT.java), [UI journey](../../spire-ui/src/components/work-items/WorkItemJourney.test.tsx) |
| 2 | An above-ceiling label is clamped and says so. Separate mutations remove the policy bound, persisted clamp and visible explanation. | 7 / round 11 | [Policy evidence](../../.claude/reviews/global/factory-m3-slice7.md), [Java policy](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemPolicyIT.java), [UI policy](../../spire-ui/src/components/work-items/WorkItemPolicy.test.tsx) |
| 3 | An unlisted applier selects no profile. Independently, an unattributed label selects nothing even when its actor hint passes membership. The positive control admits an allowed, attributed applier. | 5 / rounds 8–9 | [Intake evidence](../../.claude/reviews/global/factory-m3-slice5.md), [Intake tests](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemIntakeIT.java) |
| 4 | Lowering the ceiling stops an in-flight item at its next phase. The fixture keeps its source enabled and actor allowed; only the ceiling prevents continuation. A separate gate-answer case detects a changed policy revision. | 7 / round 11 | [Ceiling evidence](../../.claude/reviews/global/factory-m3-slice7.md), [Policy tests](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkItemPolicyIT.java) |
| 5 | Measured push access allows /fix with empty overrides and outside the legacy author list. Readers and unknown permission are refused; ALLOW grants a reader and DENY stops a writer. Six independently mutated cases enter the real saga with valid finding, target and budget prerequisites. | 4 / round 7 | [Permission evidence](../../.claude/reviews/global/factory-m3-slice4.md), [Saga tests](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/FixPermissionSagaTest.java) |
| 6 | Handle entry resolves and stores a stable ID, then renders the handle after reload. Unresolved input writes nothing. The persistence proof uses fresh JVM readers, and UI tests retain the round trip. | 3 / round 6 | [Identity evidence](../../.claude/reviews/global/factory-m3-slice3.md), [Resource tests](../../spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ActorResolutionResourceTest.java), [Actor picker](../../spire-ui/src/components/ActorPicker.test.tsx) |
| 7 | Repositories own workspace, selected accounts and one webhook per event kind. Account forms have no workspace. The migration and real-row continuity proofs retain existing credentials, identities and history. | 2 / round 5 | [Cutover evidence](../../.claude/reviews/global/factory-m3-slice2.md), [Repository detail](../../spire-ui/src/components/repositories/RepositoryDetail.test.tsx), [Account form](../../spire-ui/src/components/SettingsProviders.form.test.tsx) |

## Execution and takeover evidence

[Slice 8b](../../.claude/reviews/global/factory-m3-slice8b.md) adds real containers, a local Git
origin and actual killed worker JVMs. Item builds checkpoint without pushing; a current delivery
permit resumes only the publisher. Recovery observes a prior push without rebuilding or charging
another build. Draft delivery and REVIEW tests supply explicit TEST-only prior verification.

The separate live standalone /fix proof passed on
[TEST PR #32](https://github.com/artyomsv/spire-test/pull/32): run `4003204361:1` automatically
pushed `264ff858b538a3c779cf94161d3bc601bcfcf69a`. The next review resolved the intended GitHub
thread and persisted verdict; the six other prior findings remained UNCHANGED. The TEST PR was
closed and its exact branch deleted. The review/run audit was retained.

[Slice 9](../../.claude/reviews/global/factory-m3-slice9.md) independently mutates ordinary
approving prose, stale approval head, dismissed state, recorded bot identity after rename and
rotation, and durable publication revocation after actual JVM death without an M1 cancel claim.
A human wearing the bot's display name still takes over. Concurrent recovery records an
already-observed PR once and keeps the item suspended.

## Limits that remain

- **Production VERIFY and LAND remain unavailable.** M4 owns the verifier; M3 does not ship one.
- **No live item-build proof.** The live standalone /fix proof on TEST PR #32 does not prove an
  item-linked build against a real forge.
- **The automated GitLab run-unit gap is still open.** RunUnitSpec has no network field, so a
  run unit cannot reach the e2e stack's GitLab. A live GitHub run does not close this gap.
- **The two factory images are still not on GHCR.**
- Per-forge identity and permission limits remain separate entries in [UNVERIFIED](../UNVERIFIED.md).
  Native external gate answers and operator resume have no live deployment proof. GitLab and
  Bitbucket native PR approvals are visibly unavailable; Jira Data Center comment polling is unavailable.
- Remote publication already in progress cannot be recalled. No atomic ordering with a human's
  remote push is claimed. Confirmed deletion/transfer payloads still need live provider evidence.

## Mutation and validation records

Counts belong to their recorded slice and source revision; they are not a globally deduplicated sum.
Every ledger names its selected failure and scratch-restored passing case. The accepted slice 7
ledger explicitly separates its one test-wiring parity check from production mutations.

| Slice | Recorded mutation evidence |
|---|---|
| 1–4 | Selected-case tables in [slice 1](../../.claude/reviews/global/factory-m3-slice1.md), [slice 2](../../.claude/reviews/global/factory-m3-slice2.md), [slice 3](../../.claude/reviews/global/factory-m3-slice3.md), [slice 4](../../.claude/reviews/global/factory-m3-slice4.md) |
| 5 | [154 checks / 153 distinct production mutations](../../.claude/reviews/global/factory-m3-slice5-mutations.json) |
| 6 | [142 checks / 140 distinct production mutations](../../.claude/reviews/global/factory-m3-slice6-mutations.json) |
| 7 | [123 checks: 122 production checks / 120 distinct production mutations, plus one authorized test-wiring check](../../.claude/reviews/global/factory-m3-slice7-mutations.json) |
| 8a | [92 checks / 92 distinct production mutations](../../.claude/reviews/global/factory-m3-slice8a-mutations.json) |
| 8b | [248 checks / 245 distinct production mutations](../../.claude/reviews/global/factory-m3-slice8b-mutations.json) |
| Presentation | [30 shared-style and interaction checks](../../.claude/reviews/global/factory-m3-round14-mutations.json) |
| 9 | [78 checks / 78 distinct production mutations](../../.claude/reviews/global/factory-m3-slice9-mutations.json) |
| 10 | [Final migration and read/write guard evidence](../../.claude/reviews/global/factory-m3-slice10.md) |

Final measured test counts, backup chronology, dev continuity and migration results are in the
[slice 10 record](../../.claude/reviews/global/factory-m3-slice10.md). Repeatable upgrade steps
are in [the smoke-test runbook](../SMOKE-TEST.md#m3-final-upgrade-slice-10).
