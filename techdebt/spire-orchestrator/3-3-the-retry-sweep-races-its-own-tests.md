# The retry sweep races the tests that assert about it

**Severity:** 3 (medium) — **Impact:** 3 (a random red CI run on an untouched branch)

## What

`ReviewRetryScheduler.dispatchDueRetries` is `@Scheduled(every = "5s")` and calls
`ReviewProjection.claimDueRetries(Instant.now())`. `ReviewRetryScheduleIT` calls that same method
directly, and the orchestrator's `%test` profile does not disable the scheduler — so the production
sweep is running throughout, against the same table, claiming the same rows.

`onlyOneClaimWinsSoAnAttemptCannotBeDispatchedTwice` is the case that shows it:

```java
projection.scheduleRetry(reviewId, 2, "waiting", Instant.now().minusSeconds(1));

List<String> first = projection.claimDueRetries(Instant.now());
List<String> second = projection.claimDueRetries(Instant.now());

assertTrue(first.contains(reviewId), "the first sweep claims it");
```

The row is due the moment `scheduleRetry` returns. If the 5-second sweep lands in the window before
the test's own call, the sweep takes the claim, the test's `first` is empty, and the assertion fails
— **naming the wrong thing**, because it reads as "the claim did not work" when the claim worked
perfectly and something else made it.

## How it was found

A full `testFast testServices` run failed here once, and the same test passed alone in 32 seconds.
The test body contains no sleep, no thread and no concurrency of its own, which is what makes the
diagnosis findable: a deterministic test that fails only under load is racing something outside
itself.

Observed on 2026-09-03, on the run that added `spire-agent-image` to the service tier. That module
builds container images during its tests, so it widened the window rather than created it. Every
other suite in both tiers was green.

## Why it is not fixed here

The obvious fix is `quarkus.scheduler.enabled: false` in the orchestrator's `%test` profile, which is
the precedent `spire-run-worker` already sets — `ScheduledWorkIsDeclaredTest`'s javadoc says the test
profile disables the scheduler there, and asserts the `@Scheduled` DECLARATION by reflection instead,
precisely so a sweep nobody called cannot make an assertion true for the wrong reason.

It is a one-line change to a **global** setting, and this repository has several tests that may rely
on a sweep running. Making it late in an unrelated task, without establishing which, risks trading a
rare flake for a class of silent ones — which is the worse direction.

## What to do

1. Add `quarkus.scheduler.enabled: false` to the orchestrator's `%test` profile.
2. Run both tiers and find what breaks. Anything that does was depending on a background sweep rather
   than driving the behaviour it asserts, which is the same defect one level down.
3. For each, call the scheduled method directly and assert the `@Scheduled` declaration separately,
   the way `ScheduledWorkIsDeclaredTest` does.

## Why it matters

A flake that fires under load fires in CI, on a branch that changed nothing near it. The cost is not
the rerun — it is that a red build stops being information, and the next real failure in this file
gets rerun instead of read.
