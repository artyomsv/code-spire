# Two Dev Services modules run in parallel inside ONE Gradle invocation and produce false reds

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | root `build.gradle.kts` (the `DockerSerialisation` build service and `org.gradle.parallel`); `spire-arch/.../DockerTestsAreSerialisedTest.java` |
| Found during | M2 fork-provenance slice — twice in one session, once nearly committing over a red run |
| Date | 2026-09-04 |

## Issue

`CLAUDE.md` records that test tasks driving the real Docker daemon are serialised by a shared build
service with `maxParallelUsages = 1`, and `DockerTestsAreSerialisedTest` derives the module list by
scanning test sources so a module that starts driving the daemon and forgets to declare itself fails
the build. The declared members are `spire-runtime-docker`, `spire-run-worker`, `spire-e2e` and
`spire-agent-image`.

**`spire-gateway` and `spire-orchestrator` are not in that set, and both start Quarkus Dev Services
Postgres.** With `org.gradle.parallel=true`, `./gradlew :spire-gateway:test :spire-orchestrator:test`
in one invocation runs them concurrently against contended containers.

The symptom is the dangerous part: **tests fail that have nothing to do with the change.** Measured
twice in one session —

- a mutation of `FixTargets.belongsTo` reported `FixRunsTest.doesNotCountAnotherReviewsFixRuns`
  failing, a test in a different class that never touches `FixTargets`;
- a fork-provenance change reported six `ProviderResourceTest` failures, in a package the change does
  not touch.

Both passed on a forced re-run in isolation. The second one nearly landed a commit over a red build,
because the verification command's exit code was masked by a pipe into `grep`.

This is the same failure the existing serialisation exists to prevent, one category across: the
guard covers modules that share the **Docker daemon** and misses modules that share **Dev Services**.

## Risks

Medium. It cannot corrupt a merged result — CI runs the two tiers as separate jobs — but locally it
does two expensive things. It produces red runs whose named failures point at innocent code, which
costs an investigation each time and trains a reader to re-run rather than read. And it makes
mutation verification unreliable in exactly the direction that matters: a mutation can appear to kill
a test it did not touch, or a survivor can be masked by an unrelated failure.

`CLAUDE.md`'s own note that the lock "covers one Gradle invocation" reads as reassurance here, and is
not: the gap is **inside** one invocation, between two modules nobody thought to declare.

## Suggested Solutions

1. **Widen the shared build service to any module that starts Dev Services**, and derive that list
   the way the Docker one is derived — by scanning for the marker rather than trusting a declaration.
   `DockerTestsAreSerialisedTest` already proves the derivation approach works and already fails the
   build when a module joins the category silently; this is the same test with a second predicate.
2. **Give each service module its own Dev Services namespace** so concurrent containers cannot
   contend. Better isolation and no serialisation cost, but it is a Quarkus configuration change per
   module and the failure mode if it is half-applied is this same symptom, quieter.
3. **Document it and leave the build alone**: one service module per invocation, and say so where the
   `testServices` command is given. Cheapest, and it relies on every future reader obeying a
   convention that has already been broken twice by someone who had read it.
