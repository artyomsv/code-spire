---
name: claude-md-updated-at-pr-merge-not-per-task
description: CLAUDE.md is rewritten when a PR merges, never in a per-task commit inside an open PR — do not flag a mid-PR task commit for skipping it
metadata:
  type: project
---

`CLAUDE.md` in code-spire is touched only at PR-merge granularity. `git log --oneline -- CLAUDE.md`
shows `#95`, `#96`, `#106`, `#108`, `#109` — every entry is a merged PR, never one of the
per-task commits inside those PRs. The per-task narrative goes to `docs/HISTORY.md` and the
milestone doc (`docs/factory/ROADMAP.md`).

**Why:** `CLAUDE.md`'s Status section is a snapshot that is rewritten, not appended to
(`7c588f4 Move the delivery log out of CLAUDE.md into docs/HISTORY.md`). Rewriting it twelve times
inside one milestone would churn the file and produce a snapshot describing half a feature.

**How to apply:** when reviewing task N of M inside an open PR, a missing `CLAUDE.md` edit is NOT a
violation. Report it instead as a merge-gate item: it must land before the PR merges. Same reasoning
as [[commit-style-is-narrative-not-conventional]] — check the project's own practice before applying
the generic rule.
