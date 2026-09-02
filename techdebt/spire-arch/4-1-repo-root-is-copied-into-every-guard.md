# `repoRoot()` is copied into every guard in `spire-arch`

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Trivial |
| Location | `CoreIsProviderNeutralTest`, `ImageBuildSeesEveryModuleTest`, `ModuleLicensingIsDeclaredTest`, `PureModulesAreFrameworkFreeTest`, `RedirectHandlingHasOneHomeTest`, `TestTierCoverageTest` — each carries its own private copy |
| Found during | M1 Task 0 review (rules-compliance, DRY) |
| Date | 2026-09-02 |

## Issue

Six test classes in `spire-arch` each declare an identical private `repoRoot()`: read the
`spire.repoRoot` system property, refuse if unset, return a `Path`. The DRY rule in
`~/.claude/rules/clean-code-general.md` names three or more identical lines in two or more places as
the threshold, and this is six copies of five lines.

Task 0 introduced a seventh copy and then removed it: the new `RootBuild` helper now owns
`repoRoot()`, `read(name)` and `declaredList(name)`, and both the new guard and
`TestTierCoverageTest`'s list parsing go through it. So the home exists and is in use; the six older
copies simply have not moved yet.

They were left alone deliberately rather than swept up mid-task. Task 0's subject is a Docker daemon
race, and a seven-file mechanical refactor inside that commit would have made the diff harder to
review than the change it carried.

## Risks

- Low, and bounded. The duplication is a five-line constant-shaped method that has not changed since
  it was written, so drift is unlikely rather than impossible.
- The real cost is the next guard: a new check copies whichever neighbour it was written beside, and
  the count grows by one each time. Task 0 did exactly that before review caught it.

## Suggested Solutions

- Replace each private `repoRoot()` with `RootBuild.repoRoot()` and delete the copies. Mechanical,
  compiler-checked, and the existing guards' own tests cover the result.
- While there: `ImageBuildSeesEveryModuleTest` and `TestTierCoverageTest` both parse
  `settings.gradle.kts` for `include("…")`. That parser is the same shape as `declaredList` and could
  join `RootBuild` in the same pass.
