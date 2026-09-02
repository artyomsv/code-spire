# Twenty-two javadoc blocks document nothing, and no check can see it

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | Twenty-two sites across twelve modules; scanner in the Suggested Solutions below |
| Found during | M1 Task 7/8 four-lens review (rules, code quality) |
| Date | 2026-09-03 |

## Issue

Inserting a method between an existing javadoc block and the method it described leaves **two
consecutive javadoc blocks**. Java attaches only the LAST one, so the first is discarded and the
method it was written for becomes undocumented — while the new method silently inherits a
description of something else.

It compiles, it reads correctly in a diff, and nothing in this build catches it: there is no
checkstyle, no spotless, and `javadoc` is not run. Three instances were introduced by the M1 Task 7
and Task 8 commits alone and were found by reading rather than by tooling — one of them left
`DockerRunRuntime.steer`, which throws immediately, carrying a block explaining that it "waits for
the agent within the unit's wall clock, stops it if it overran, then drains the publisher".

That last one is why this matters more here than in most codebases. This project's javadoc is its
design record: the discarded blocks are typically the long ones carrying the history of a defect
already fixed once, and losing them loses the argument for why the code is shaped as it is. The
`RunLauncher.unobserved` block that went orphaned records exactly which earlier version of that
method was wrong and how.

Scanned repo-wide after fixing the three in the M1 diff: **22 remain**, in `spire-contract` (4),
`spire-orchestrator` (7), `spire-scm-*` (4), `spire-review-worker` (2), `spire-gateway` (1),
`spire-context-code` (1), and three in test sources.

## Risks

- A reader trusts a block that describes a different method, which is worse than no documentation.
- The design record this project deliberately keeps in javadoc is lost silently, one method at a
  time, and only ever noticed by someone reading that exact spot.
- The same edit shape recurs on every commit that inserts a method above an existing one, so the
  count grows.

## Suggested Solutions

- Fix the 22, then add the guard — in that order, because a guard added first fails the build on
  pre-existing sites and would have to ship disabled, which is how a check becomes permanent noise.
- The guard is small and needs no parser: a javadoc block whose closing `*/` is followed (ignoring
  blank lines) by another `/**` is always an orphan, because nothing valid can sit between two
  javadoc blocks. It belongs in `spire-arch` beside the other source-text scans, which already
  establish the precedent of scanning source rather than bytecode, and must declare the scanned tree
  as a Gradle input — `ContractSchemaSnapshotTest`'s own history records a cached pass reported after
  the very change it should have caught.
- Guard against vacuity the way the neutrality scan does: assert the scan reached a non-zero number
  of files, and pin one deliberately-orphaned fixture so a broken matcher fails rather than passes.
