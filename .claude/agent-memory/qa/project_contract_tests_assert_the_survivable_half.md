---
name: contract-tests-assert-the-survivable-half
description: Shared/abstract SPI contract tests in this repo tend to assert only the half of a two-part claim that survives a no-op — check each name's verbs against its assertions
metadata:
  type: project
---

An abstract SPI conformance test (`RunRuntimeContract` in `spire-runtime/src/testFixtures`,
`HarnessAdapterContract` in `spire-harness`) is written so a second arm inherits the rules. The
recurring defect is that a test named for TWO properties asserts only the one that a no-op
implementation also satisfies.

Worked example found on `chore/factory-m1-debt` (PR #106):
`RunRuntimeContract.cancelStopsAUnitWithoutDestroyingIt` asserts only
`discoverUnits().contains(runId)`. `DockerRunRuntime.discoverUnits` uses
`listContainersCmd().withShowAll(true)`, so a stopped, running or never-touched container all
appear — a `cancel` that is a complete no-op passes. The proof needs no mutation run: the sibling
`salvageNeverDestroys` makes the IDENTICAL assertion after a call that provably does not stop
anything.

**Why:** the contract is the artifact that stops arms disagreeing, so a rule that a no-op satisfies
is worse than no rule — a future Kubernetes arm (M5) inherits a green suite and an unimplemented
capability.

**How to apply:** for every contract/conformance test, read the method NAME as a conjunction and
find the assertion for each conjunct. If one conjunct has no assertion, either add one or move it
into the contract's explicit "what this deliberately does NOT cover" list — that list already
exists in `RunRuntimeContract`'s javadoc and is the right home. Also check whether the SPI can
even express the missing half; if it cannot, that is the finding.

Related: [[project_code_spire_test_gap_pattern]].
