# M4 verify slice 1: mutation checks

Run 2026-10-08 against PR #184 at `7da06846`. Each mutant broke one production line on its own branch, with a
draft PR against `feat/m4-verify`, and CI ran the full suite. The baseline (PR #184 at `7da06846`) passed every
check. The operator chose these ten: the four final-review fixes and six verdict and security guards.

| # | Production line broken | Killed by | CI run |
|---|---|---|---|
| 1 | `DockerVerifyUnits.container`: `withWorkingDir(/workspace)` removed | `DockerVerifyIT.aCheckRunsInTheVerifyWorkspaceWhateverTheImagesWorkdir` | 37696778724 |
| 2 | `VerifyOutcomes.classify`: passing checks keep the full 64 KiB tail | `VerifyOutcomesTest.twentyLongOutputsStayWellUnderTheBrokersRecordLimit` | 37696787720 |
| 3 | `WorkItemTransitions.verified`: verify wall seconds set to 0 | `WorkVerifyIT.verifyTimeCountsTowardTheItemsWallClock` | 37696796532 |
| 4 | `WorkRunWorker.readableStart`: always keeps the checkpoint start | `WorkRunWorkerTest.aRetryWhoseCheckpointUnitIsGoneStartsFromTheBase` | 37696806698 |
| 5 | `VerifyOutcomes.reason`: prepared head not compared to the asked head | `VerifyOutcomesTest.aPreparedHeadOtherThanAskedIsNeverPassed` | 37696813531 |
| 6 | `VerifyUnitSpec`: credential-name rule disabled | `VerifyUnitSpecTest.aCheckCarriesNoCredentialVariable` | 37696824923 |
| 7 | `WorkVerification`: PASSED without "every check exited 0" | `WorkVerificationTest.passedNeedsEveryCheckToExitZeroAndNoReason` | 37696834430 |
| 8 | `WorkDelivery.passed`: a bare attempt id counts as passed | first run **survived**; after `WorkDeliveryPassedTest` was added: `aBareAttemptFromHistoryDoesNot`, `aFailedOrUnverifiedResultDoesNot` | 37699274876 |
| 9 | `WorkItemTransitions.verified`: a pass of other than the bound checks accepted | `WorkVerifyIT.aPassOfOtherChecksThanTheBoundOnesIsRefused` | 37696851564 |
| 10 | `WorkItemTransitions.answer`: a verify result gate treated as an ordinary gate | 5 tests, all retry/stop behaviour of that gate, incl. `WorkVerifyIT.retryBuildRewindsToBuildAndCountsARun` | 37696860516 |

**Mutant 8 survived** because the transitions refuse the same evidence before delivery is reached, so no
journey test can reach the delivery guard with a bare attempt. The guard is now tested on its own
(`WorkDeliveryPassedTest`); the rerun is killed by its two discriminating cases.

**Mutant 10 killed five tests.** The line it breaks is what every retry and stop answer depends on, so each of
those tests fails, for the reason its name gives. That is the expected spread for this guard.

**Result:** 10 of 10 mutants killed, one after a test was added. PR #184 at the head with that test passes every CI check.
