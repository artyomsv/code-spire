# `ReviewRetryScheduleIT` races the scheduler that runs beside it

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-orchestrator/src/test/java/dev/codespire/orchestrator/pipeline/ReviewRetryScheduleIT.java:74` (`aFailedDispatchCanBePutBackOnTheClock`), against `ReviewRetryScheduler:46` (`@Scheduled(every = "5s")`) |
| Found during | M1 Task 2 review fixes — an unrelated red in a full-module run |
| Date | 2026-09-02 |

## Issue

The test schedules a retry due one second in the past, claims it, reschedules it into the past
again, and asserts it is claimable a second time. `ReviewRetryScheduler` is live in the test profile
and sweeps every five seconds, claiming whatever is due. When its tick lands between the test's
reschedule and its second claim, the scheduler takes the row and the assertion fails.

Observed once in a full `:spire-orchestrator:test` run and green in isolation on the next run. The
test is untouched by the branch that surfaced it — `git log` puts its last change several
milestones back — so this is a latent race, not a regression.

The failure is unhelpful in the way that matters: `expected: <true> but was: <false>` on a test
about retry bookkeeping reads as a broken retry, and sends the next person to the projection rather
than to the clock.

## A second method, and a second date (2026-09-03)

`onlyOneClaimWinsSoAnAttemptCannotBeDispatchedTwice` exhibits the same race. It schedules a retry
due one second in the past and claims it twice, asserting the first call wins; when the live sweep
lands in the window before the first claim, the row is already gone and `first` is empty.

Observed in a full `testFast testServices` run on the branch that added `spire-agent-image` to the
service tier. That module builds container images during its tests, so it widened the window rather
than created it — every other suite in both tiers was green, and this test passed alone in 32
seconds.

A duplicate entry for this was filed the same day and deleted: nobody searched first, which
`techdebt/README.md` names as the specific failure it exists to prevent. Worth recording, because
the duplicate also reasoned from the WORSE of the two fixes below — it proposed disabling the
scheduler globally and then argued against itself on the grounds that other tests might depend on
the sweep. The first suggestion here does not have that problem.

`ScheduledWorkIsDeclaredTest` in `spire-run-worker` is the precedent for the second suggestion: its
test profile disables the scheduler and it asserts the `@Scheduled` DECLARATION by reflection
instead, so a sweep nobody called cannot make an assertion true for the wrong reason.

## Risks

- An intermittent red on an unrelated change, which is the shape that teaches a team to re-run
  rather than read a failure.
- It is worse than an ordinary flake because a genuine regression in `claimDueRetries` would produce
  exactly the same message.

## Suggested Solutions

- Give the fixture a due time the live scheduler will not reach — schedule into the future and claim
  with an explicit `Instant` argument, which `claimDueRetries` already accepts. The test then never
  depends on what the background sweep is doing.
- Or disable the scheduler for this class (`quarkus.scheduler.enabled=false` via a test profile), so
  the only thing claiming rows is the test itself.
