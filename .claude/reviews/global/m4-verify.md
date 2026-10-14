# M4 verify — branch review state (PR #184)

Base `ea1288bb`, branch `feat/m4-verify`. Round 0 was the whole-branch review of the plan run;
rounds 1 and 2 are the reviews of the pushed branch. Every fix below has a test; CI ran it.

## Round 1 — reviewed `a2b72376`, resolved in `658ada87`, `c369d215`, `8e75a183` (2026-10-09)

| Finding | Resolution |
|---|---|
| C1 a not-passed result with an empty policy wedged the item | Resolved: `not_eligible`; tracker outage → 503, retried by the sweep; the recover loop catches per row |
| I1 a silent verify never ended | Resolved: `WorkVerifyDeadline` sweep → UNVERIFIED `verify_could_not_run` past the limit + 15 min |
| I2 terminal review statuses were unnamed | Resolved: `review_refused` / `cancelled` / `superseded` / `observed` |
| I3 markReady retried a closed PR every sweep | Resolved: `review_pr_not_open`, refusals retried every 10 minutes |
| I4 a retry prompt promised commits the base start could not give | Resolved: `fromBaseInstead(note)` and `BASE_FALLBACK_NOTE` |
| H-1 `WorkRunAssembly` used `Optional.get` | Resolved |
| H-2 `ensureMarkedReady` duplicated the refusal path | Resolved |
| H-3 `DockerVerifyUnits` flag parameter | Resolved: `prepareContainer` / `checkContainer` |
| M-1, M-2 seat renewal 400 and empty refresh token untested | Resolved: `SeatRenewalTest` |
| M-4, M-5 javadoc wrong | Resolved |
| M-6 ADR-042..046 missing from CLAUDE.md | Resolved |
| M-7 three copies of the verify command rule | Resolved: `VerifyCommands` |
| M-8 `DecisionPanel` word map and `headingFor` | Resolved |
| M-9 `WorkVerifyDispatcher.claim` too long | Resolved: `isStillVerifying` / `commandFor` |
| M-10 methods and classes over the size rules | Recorded: `techdebt/global/4-2-m4-verify-grew-methods-and-classes-past-the-size-rules.md` |
| L-1..L-3 (security), S1–S6, QA L-3..L-7, rules L-11, L-13..L-15 | Open, Low |

## Round 2 — reviewed `8e75a183`, resolved in `735599ed` (2026-10-14)

Tests first in `d3ddc7ae`; CI run 38529221506 showed four of them failing on the line that names
the defect (orchestrator and runtime-docker). The run-worker tests did not run in that build, because
Gradle stopped at the first failed module.

| Finding | Resolution |
|---|---|
| H-1 the orphan watchdog stopped a live verify | Resolved: the verify registers its held build as executing; `aLiveVerifyIsExecutingUntilItEnds` |
| M-1 a late result overrode the stored one | Resolved: a repeated delivery applies the stored result; `aLateResultCannotOverrideTheStoredOne` |
| M-2 verify ignored the item's remaining wall time | Resolved: `Math.min(timeout, remaining)`; `verifyIsGivenOnlyTheItemsRemainingTime` |
| M-3 a retired workspace removal was not owed durably | Resolved: `retire` sets `release_pending`, recovery calls `releaseRetired`; two tests |
| M-4 a slow refusal rejected a newer sign-in | Resolved: `rejectRenewal` compares the ciphertext; `aRefusalOfASeatSignedInAgainMeanwhileLeavesTheNewSignInInUse` |
| M-5 long output kept its start, not its end | Resolved: `RollingTail` drops the oldest text; `DockerVerifyTailTest` |

CI on `735599ed`: all 17 checks passed (fast tests, service tests + packaging, dashboard, the four
service modules, CodeQL, Semgrep, gitleaks, manifests, kind install).

## Round 3 — re-review of `735599ed` (2026-10-14)

Five findings verified fixed. M-3 stayed open: `releaseRetired` skipped the removal when the daemon
listed no container, then cleared the owed flag, so volumes left by a teardown that stopped after
removing the containers were never removed. Fixed as `releasePublished` does: the hold is asked by
run id, and removed when present. Test `recoveryRemovesARetiredWorkspacesSurvivingVolumes` first in
`f611c97c`, the fix after it.
