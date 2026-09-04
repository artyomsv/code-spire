---
name: gradle-rerun-required
description: Every Gradle :module:test in code-spire needs --rerun ATTACHED TO EACH TASK, or it reports BUILD SUCCESSFUL having executed zero tests
metadata:
  type: feedback
---

Always pass `--rerun` to a `:<module>:test` invocation in this repo, and attach it **immediately
after each task name**, not once at the end of the command line. Without it Gradle's up-to-date
check skips execution entirely and still prints `BUILD SUCCESSFUL`.

```
./gradlew :spire-contract:test --rerun :spire-workspace:test --rerun   # correct
./gradlew :spire-contract:test :spire-workspace:test --rerun           # WRONG - reruns nothing
```

**Why:** two separate false greens, both observed directly.

1. Round-3 QA pass on `feat/software-factory`: a plain `./gradlew :spire-contract:test` printed
   `UP-TO-DATE` and `BUILD SUCCESSFUL in 24s` with no test having run.
2. Task-1 QA pass (Gradle 9.7.1): 19 test tasks on one command line with a single trailing
   `--rerun` gave `BUILD SUCCESSFUL in 3s`, `64 actionable tasks: 1 executed, 63 up-to-date`.
   `--rerun` is a **task option**, so a trailing one binds only to the last task. Repeating it per
   task gave `19 executed` and 49s of real work.

A lifecycle task (`testFast`, `testServices`) cannot be forced this way at all — `--rerun` on it
reruns the empty lifecycle task, not the `:module:test` tasks it depends on. Expand the tier into
its module list from `build.gradle.kts` (`fastTestModules` / `serviceTestModules`), **or simply use
the global `--rerun-tasks`**, which is the cheaper answer and needs no list kept in sync:

```
./gradlew testFast testServices --rerun-tasks    # correct for the tiers
./gradlew testFast --rerun testServices --rerun  # WRONG - 118 tasks all UP-TO-DATE, BUILD SUCCESSFUL in 9s
```

Measured on the PR #96 whole-PR pass (2026-09-03): the `--rerun` form reported
`118 actionable tasks: 118 up-to-date` in 9s; `--rerun-tasks` reported
`118 actionable tasks: 118 executed` in 10m 1s and 25 real test tasks. Two wasted runs before
re-reading this file — **read this memory BEFORE launching the first build, not after.**

**How to apply:** after the run, read counts from the JUnit XML in
`<module>/build/test-results/test/*.xml` (`tests=`, `failures=`, `errors=`, `skipped=` on
`<testsuite>`), never from console text, and cross-check the `N executed` line against the number
of test tasks you named. Also confirm the specific new test classes appear as XML files — a suite
that silently did not run leaves no file. See
[[feedback-mutation-testing-restore-discipline]] for the sibling rule about probing.
