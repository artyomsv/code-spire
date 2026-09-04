# Memory index

- [Commit style is narrative, not Conventional](commit-style-is-narrative-not-conventional.md) — missing `type(scope):` prefix is house style; LOW at most, never HIGH
- [Debt entries need a Location check](factory-review-debt-deletions-need-location-check.md) — read the entry at `origin/master`, not the commit or plan claiming to close it; works both directions
- [Whole-PR doc drift lives in the design docs](whole-pr-doc-drift-lives-in-the-design-docs.md) — a correcting commit fixes the PRD and misses EXECUTION-LAYER/ROADMAP/README; grep the vocabulary repo-wide
- [CLAUDE.md updated at PR merge, not per task](claude-md-updated-at-pr-merge-not-per-task.md) — a mid-PR task commit skipping CLAUDE.md is a merge-gate item, not a violation
- [No project rules dir, no lint gate](no-project-rules-dir-and-no-lint-gate.md) — rules come from ~/.claude only; no modernizer/checkstyle, so never claim a red pipeline
- [Run-unit env vars are not .env.example keys](run-unit-env-vars-are-not-env-example-keys.md) — SPIRE_* built by RunUnitBuilder; check SMOKE-TEST + the ADR instead
- [Working tree may hold transient mutation state](working-tree-may-hold-transient-mutation-state.md) — a missing guard or a stray `.orig` is usually qa mid-run; verify with `git show HEAD:`
- [ChargeKind is the precedent for run-kind literals](chargekind-is-the-precedent-for-run-kind-literals.md) — cite it when flagging `"BUILD"`/`"FIX"` strings in factory writers
- [`*Entry` is an established transport-type name](entry-is-an-established-transport-type-name.md) — check DlqEntry/TimelineEntry before raising dto-naming on a new orchestrator record
