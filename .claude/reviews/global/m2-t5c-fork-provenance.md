# Code Review State: global / m2-t5c-fork-provenance

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over `a365d79` and `2cac818` on `feat/factory-m2-deliver` (PR #119) — fork provenance across
the three ingresses, `V55`, and the pushable rule. Fixes in `182f3bd` and `cf1f6fe`.

**Both lenses measured `2cac818`, and `182f3bd` landed while they ran.** Roughly half of what came
back was already closed: the argument-identity survivors (witness fakes), `belongsTo` being dead
code, the `ON CONFLICT` columns, the `RunIds.of` guard, `RunKind`, the `"OPEN"` literal, the
`isBlank`/`isEmpty` asymmetry on `commit`, the vacuous `contains("scm\"")` assertion, the
`branch == protectedBranch` refusal, and the unused `TargetFinding` parameter. Recorded here so a
later round does not read that half as ignored.

**Security and qa independently found the same top defect by different routes** — security by
reading the three `NOT NULL DEFAULT ''` columns and noticing only two were guarded, qa by adding a
fifth cause to the matrix and watching it survive.

## Resolved (fixed in code; do not re-raise)

- [security/M2 + qa/#2] `dest_branch` unguarded in `whyNotPushable` — it becomes
  `Planned.protectedBranch`, and `ExecuteRun`'s compact constructor throws on a blank in `existing`
  mode, so the wiring commit would have raised an exception where a `Refused` belongs. On a Kafka
  consumer that is a redelivery: refusing forever, silently. — round 1 (`cf1f6fe`)
- [qa/#2b] The 36-case matrix had no `destBranch` axis and no null-provenance axis; both added
  (162 cases). Re-confirmed during verification that dropping the `destBranch` clause fails the two
  dedicated tests and leaves the matrix **green** — the matrix cannot see a cause it does not vary.
  — round 1 (`cf1f6fe`)
- [security/M5] `V55`'s `from_fork DEFAULT false` became load-bearing the moment `FixDispatch`
  consumed it. Column is now nullable with no default; `FixTargets` reads it with `getObject` (not
  `getBoolean`, which maps SQL NULL to false); a new `PROVENANCE_UNKNOWN` cause refuses old rows with
  wording that does **not** claim the pull request is a fork. ADR-023's "unknown is never zero"
  applied to a boolean. — round 1 (`cf1f6fe`)
- [security/L1 + qa/#6.3] `(existingBranch=false, protectedBranch="develop")` is representable and
  was silently dropped by `publisherEnvironment`, while the publisher honours the variable in every
  mode. Now written whenever non-blank. — round 1 (`cf1f6fe`)
- [qa/#4] The unrecognised-SCM refusal had no test; every case named `"github"`, so deleting the
  branch left the suite green and moved the failure into `Optional.get()`. — round 1 (`cf1f6fe`)
- [security/L3] No legacy-JSON wire test for `ExecuteRun`. A round trip proves only that the new
  version agrees with itself; under ADR-014's short retention the in-flight payload during a rolling
  upgrade is written by the OLD version. — round 1 (`cf1f6fe`)
- [security/L4] `RunUnitBuilderTest` used `env.toString()` as an assertion message; that map holds
  `SPIRE_GIT_SECRET`. Now `env.keySet()`. — round 1 (`cf1f6fe`)
- [security/L6] The stale-`pr_state` re-open gap was recorded only in a javadoc on the class that has
  it. Now in `docs/UNVERIFIED.md` §E and in ADR-040's consequences. — round 1 (`cf1f6fe`)
- [orphaned javadoc, ×2] `isPushable`'s doc had drifted above `whyNotPushable` when the boolean became
  a derivation, and `ExecuteRun` carried two stacked javadocs. The recorded trap, both re-homed.
  — round 1 (`cf1f6fe`)

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [security/M3] A long-lived shared SOURCE branch (a `develop → main` release pull request) passes
  every check: open, not a fork, real refs. ADR-040's "the destination is the truth" covers `develop`
  only as a destination. **Filed rather than dismissed** —
  `techdebt/spire-orchestrator/3-3-a-long-lived-shared-branch-passes-every-fix-check.md`. Not fixed
  in this round because the cheap version (a `never-push` glob) is operator configuration that wants
  a startup story, and the correct version needs the same dispatch-time forge re-read as the
  stale-`pr_state` gap. One design, not two. (round 1)
- [security/L2] `QueuedRun`'s canonical constructor can be half-applied and is caught only by the
  database CHECK. `asFixFor` already refuses blanks; the canonical path is the projection's own
  internal call, and adding a compact-constructor guard would duplicate V54's two-arm CHECK in Java
  where the two could drift. The CHECK is the single encoding on purpose. (round 1)
- [qa/#7] `target.providerType()` → `""` in `Planned` survives. Measured at `2cac818`;
  `plansARunThatPushesToThePullRequestsOwnSourceBranch` asserts `assertEquals("github",
  planned.providerType())` as of `182f3bd`, so this was already closed when reported. (round 1)

## Verification

`:spire-contract:test` · `:spire-orchestrator:test` (`Fix*`, `IntegrationSagaPolicy`,
`FactoryRunProjection` — 128 tests) · `:spire-run-worker:test` (`RunUnitBuilderTest` — 24 tests), all
0 failures. Six mutations, each killing exactly its intended test:

| Mutation | Fails |
|---|---|
| Drop `\|\| destBranch.isBlank()` | `refusesARowWhoseDestinationBranchWasNeverRecorded` + `refusesABlankDestinationRatherThanThrowingLater` (matrix stays **green** — the point) |
| `getObject` → `getBoolean` | `refusesARowWrittenBeforeTheDeploymentCouldSeeForks` |
| Drop the `fromFork == null` arm | that test + the matrix's null axis + `refusesARowWhoseProvenanceWasNeverRecordedWithoutCallingItAFork` |
| Disable the unrecognised-SCM refusal | `refusesAReviewRecordedUnderAnScmThisBuildDoesNotKnow` |
| Re-gate the protected branch on the mode | `aProtectedBranchIsNotDroppedJustBecauseTheModeIsTheDefault` |
| Drop the null-`protectedBranch` normalisation | `aCommandSerialisedBeforeAdr040ReadsAsNamespaceMode` + `existingModeWithoutAProtectedBranchIsRefused` |
