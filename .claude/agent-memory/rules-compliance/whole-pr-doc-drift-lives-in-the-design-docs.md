---
name: whole-pr-doc-drift-lives-in-the-design-docs
description: On Code Spire whole-PR rounds, the doc a correcting commit forgets is the design doc (docs/factory/*, ROADMAP, README) — check every doc naming a mechanism, not just the one the commit touched
metadata:
  type: project
---

When a Code Spire commit corrects a claim ("Stop claiming X"), it reliably updates the PRD and the
techdebt entry and reliably MISSES the design docs — `docs/factory/EXECUTION-LAYER.md`,
`docs/factory/ROADMAP.md`, `docs/DECISIONS.md`, and the root `README.md`. Verified on PR #96:
`17125ea` corrected `PRD.md` and `ARCHITECTURE.md` about the credential pool's unimplemented
`rejected`/`rate_limited` producers, and left the state diagram at `EXECUTION-LAYER.md:125` and the
M1 "Delivers" bullet at `ROADMAP.md:132` asserting both transitions as automatic.

**Why:** per-task reviews read the task's own slice, so a claim repeated in five documents is only
ever checked in the one the task edited. The root `README.md` is worse — nothing in the per-task
loop touches it, so its licence table and service list drift a whole milestone behind `LICENSING.md`.

**How to apply:** on any whole-PR round, take each mechanism the PR admits is unimplemented (read
`docs/UNVERIFIED.md` first — it is the index) and `git grep` its vocabulary across ALL of `docs/`
and `README.md`, not just the files the diff touched. A doc asserting a mechanism the code lacks is
HIGH in this project. Same technique as [[factory-review-debt-deletions-need-location-check]].
