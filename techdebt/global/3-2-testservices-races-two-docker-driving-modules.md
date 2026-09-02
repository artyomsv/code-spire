# `testServices` races two Docker-driving modules against one daemon

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `gradle.properties:6` (`org.gradle.parallel=true`), `build.gradle.kts:202` (`testServices`), `spire-runtime-docker/src/test/java/dev/codespire/runtime/docker/DockerRunRuntimeIT.java`, `spire-run-worker/src/test/java/dev/codespire/runworker/M0WalkingSkeletonTest.java` |
| Found during | Verifying the master merge on PR #95 before pushing |
| Date | 2026-09-02 |

## Issue

`./gradlew testServices` is the project's own documented pre-commit command, and it runs the
five service modules' test tasks with `org.gradle.parallel=true`. Two of those modules drive a
real Docker daemon: `spire-runtime-docker` (`DockerRunRuntimeIT`) and `spire-run-worker`
(`M0WalkingSkeletonTest`, which additionally builds three images).

Run together, `DockerRunRuntimeIT` fails intermittently. Observed on this branch:

```
anAgentThatOutlivesItsWallClockIsActuallyStopped
  NotFoundException: Status 404: No such container: 8d1af344...   (inspect, IT:276)
everyPartOfAUnitIsDestroyedByLabelWithNoMemoryOfIt
  expected: <true> but was: <false>                                (IT:299)
```

The same suite run alone is green, 15 of 15, twice on the same tree and the same daemon. So the
failures are interference or daemon pressure, not a defect in the code under test.

The specific hazard is that both failures LOOK like real container-lifecycle bugs. One says a
container vanished, the other says a destroy did not remove anything. Those are exactly the
symptoms the runtime is written to prevent, so the natural reading is that the runtime broke.

## Risks

- The documented pre-commit loop reports red on a healthy tree. That is expensive twice: it
  costs the time to diagnose, and it teaches the team to re-run rather than read a failure,
  which is how a genuine container-lifecycle regression gets waved through.
- CI runs the same task. A flaky required check invites merges on a re-run rather than a fix.
- The blast radius grows as the factory adds Docker-driving suites.

## Suggested Solutions

1. Put both suites in one Gradle "docker daemon" resource group so they serialise while the
   other three service modules still run in parallel. `Test` tasks can share a
   `BuildService` with `maxParallelUsages = 1`, which is the narrowest fix and keeps the
   parallelism that makes `testServices` fast.
2. Make the IT's assertions robust to a busy daemon: treat a `NotFoundException` on inspect as
   "already gone" where the test only wants absence, and poll for removal rather than asserting
   it on the first read.
3. Weakest, but worth stating so it is not chosen by accident: dropping `org.gradle.parallel`
   fixes it and slows every other build, and it would hide the interference rather than name it.

Prefer 1, with 2 as defence in depth. Both are wanted: 1 removes the race, 2 stops the next
one from reading as a lifecycle bug.
