# Code Review State: global / m1-task0-docker-serialisation

Last reviewed: 2026-09-02
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [sec-t0 1 / cr-t0 1 / rules-t0 (docs)] The guard scanned `spire-*/src/test` while `spire-arch` declared only `src/main` as a task input, so a module that started driving the daemon left `:spire-arch:test` UP-TO-DATE and the guard silent — a cached pass from the exact edit it exists to catch. Declared, and **measured both ways**: without the include the task is UP-TO-DATE and BUILD SUCCESSFUL; with it the derivation test fails — round 1
- [rules-t0 2 / sec-t0 2] Three places claimed the test proves `org.gradle.parallel` stays on. It lives in `gradle.properties`, which the test never read, so `org.gradle.parallel=false` — the cheap fix the design rejects — passed everything. Now asserted, and `gradle.properties` is a declared input — round 1
- [cr-t0 2 / sec-t0 2] `usesService(dockerDaemonLock)` was a bare `contains`, so moving it out of the `if (project.name in dockerDrivingModules)` guard serialised every test task and still passed — `GUARDED_USE` matches the guarded shape — round 1
- [cr-t0 3 / rules-t0 4 / sec-t0 2] `maxParallelUsages` was matched file-wide, first match wins, so a second shared service declaring it would answer for the lock while the lock drifted — anchored to the lock's own `registerIfAbsent` block — round 1
- [cr-t0 4] The root build was read raw, so a commented-out `usesService(...)` passed every assertion — comment-stripped with `JavaSource.withoutComments` — round 1
- [cr-t0 5 / rules-t0 3] `theOtherServiceModulesAreNotSerialised` hardcoded three names and passed vacuously when the declaration failed to parse — derived from `serviceTestModules`, and `RootBuild.declaredList` now asserts it matched rather than returning an empty set — round 1
- [cr-t0 7] The CLI marker required a closing quote and missed the single-string `"docker compose -f …"` form already present in `spire-e2e/…/Stack.java` — widened to `"docker[ "]` — round 1
- [cr-t0 8] The scan walked `src/test`, reading four real `.java` fixtures under `src/test/resources` — now `src/test/java` — round 1
- [cr-t0 6 / rules-t0 1 / sec-t0 3] The deleted debt entry asked for two fixes and said both were wanted; only the lock shipped — the other half re-filed as `techdebt/spire-runtime-docker/4-2-docker-it-assertions-are-not-robust-to-a-busy-daemon.md` — round 1
- [rules-t0 5] List parsing reimplemented `TestTierCoverageTest.tierList()` line for line — extracted to `RootBuild`, used by both — round 1
- [rules-t0 6] The new build-enforced guard was named nowhere in CLAUDE.md while every comparable guard is — added to Conventions — round 1
- [cr-t0 9, 10 / rules-t0 10] Comments stated the pre-scan belief ("two test tasks") and cited wrong evidence for the marker choice; the Testcontainers blind spot was undocumented — all corrected — round 1
- [cr-t0 12, 13] The per-Gradle-invocation bound and the whole-task granularity were implied rather than stated — both now written down in the class javadoc and CLAUDE.md — round 1
- [cr-t0 14] `testServices`'s description said "three deployables" with five modules in the list — corrected — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- [cr-t0 11] `Pattern.DOTALL` on the declaration regex is inert. True, and it left with the regex itself when list parsing moved to `RootBuild` — nothing to carry forward (round 1)
- [rules-t0 7, 8] Commit subject carries no Conventional Commits type, and the body wraps past 72. Both reported by the agent as informational: the repository has ~1300 plain imperative commits and the binding personal rule caps only the first line, which is satisfied (round 1)
- [rules-t0 9] `fastTestModules`'s comment calling the service tier "the three deployables" is pre-existing and outside this commit; the adjacent task description it sat next to was corrected instead (round 1)

## Open (tracked as techdebt/ entries; not fixed in this round)
- [rules-t0 5, pre-existing half] `repoRoot()` is copied into six other guards in this module — `techdebt/spire-arch/4-1-repo-root-is-copied-into-every-guard.md`
- [cr-t0 6 / rules-t0 1 / sec-t0 3, remaining half] `DockerRunRuntimeIT`'s assertions are not robust to a busy daemon, which the lock does not cover — `techdebt/spire-runtime-docker/4-2-docker-it-assertions-are-not-robust-to-a-busy-daemon.md`

## Notes
- **qa-t0 delivered no report.** It failed on a session limit mid-run ("You've hit your session limit"). Its section is therefore unknown, not clean. The build verification it was asked for was carried out directly instead: the cached-pass reproduction measured in both directions, five mutations each caught by exactly the intended test, `testFast` green, and `testServices` green at 1260 tests before the fixes.
