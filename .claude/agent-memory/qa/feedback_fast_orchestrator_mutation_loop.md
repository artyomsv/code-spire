---
name: fast-orchestrator-mutation-loop
description: A git-archive copy plus a --tests filter gives a ~65s spire-orchestrator mutation loop, so a full vacuity sweep is cheap
metadata:
  type: feedback
---

Mutate on a `git archive HEAD | tar -x -C <scratchpad>/probe` copy, then run
`./gradlew :spire-orchestrator:test --rerun --tests '*SuiteA' --tests '*SuiteB' …`.

Measured on this machine: **1m05s per iteration** for 7 @QuarkusTest suites / 111 tests (the copy
reuses `~/.gradle` caches, and all `@QuarkusTest` classes share one Quarkus boot). A full
`testFast + testServices --rerun-tasks` baseline is 9m39s by comparison, so a 9-mutation sweep costs
less than one extra baseline.

**Why: ** the working-tree contract forbids mutating the repo, and the obvious alternative — a whole
separate `--rerun-tasks` build per mutation — makes a sweep look unaffordable and gets skipped.

**How to apply: ** read kills straight from the Gradle log (`grep -E 'FAILED$'` gives
`Suite > test() FAILED`), not from the JUnit XML. Keep pristine copies of each mutated file beside
the probe and `diff -q` back to them after the last run. Related:
[[mutation-testing-restore-discipline]], [[gradle-rerun-required]].

**A SUBSET copy needs the root's `advisoryOverrides` block copied with it.** Archiving only two
modules and writing a synthetic root `build.gradle.kts` makes Gradle resolve the *unforced* jackson
(2.18.3 via docker-java), which is not in `~/.gradle` — so `--offline` fails on
"No cached version ... available for offline mode" before a single test runs. Paste the root's
`advisoryOverrides` map + `subprojects { resolutionStrategy.eachDependency { … } }` into the
synthetic root and drop `--offline`. Measured on `spire-agent-image`: ~10s per fast-tier iteration,
~40s when the Docker-driving `ReferenceImageIT` is in the filter.

**A mutation that fails to apply is indistinguishable from a mutation that survives.** On the M2-T1
pass the `git archive` copy came out **CRLF** (630 CRLF / 630 LF in `IntegrationSaga.java`) while the
anchor strings in the mutation script were LF-only. Every `s.includes(GATE)` was false, the script
exited 2, the driver had no `set -e`, and four runs went green against **pristine** source — four
false "SURVIVED" verdicts that would have been reported as vacuous tests.

Two rules, both cheap:

1. Build every anchor through `const crlf = t => t.replace(/\n/g, '\r\n')`. Check the tree first:
   `node -e 'const b=require("fs").readFileSync(f,"utf8");console.log((b.match(/\r\n/g)||[]).length)'`.
2. **Make the script prove it mutated**, and treat a no-op as fatal:
   ```js
   const changed = s !== fs.readFileSync(PRISTINE, 'utf8');
   console.log('applied ' + which + '  sourceChanged=' + changed);
   if (which !== 'PRISTINE' && !changed) { console.error('FATAL: no-op'); process.exit(3); }
   ```
   Then read `sourceChanged=true` in the results file before believing any green. A green run whose
   line says `sourceChanged=false` is a broken probe, not a surviving mutation.
