---
name: adr-040-source-branch-scope-position
description: ADR-040 puts a PR's SOURCE branch in scope even when it is a shared long-lived branch (develop->main release PR); the trunk list is a convention and the protected-branch variable covers only the DESTINATION. Raised MEDIUM in round 1 of m2-t5b-dispatch (2026-09-04)
metadata:
  type: project
---

ADR-040 §2 says the publisher's trunk list (`main`, `master`) is "a convention list, not a truth"
and that `SPIRE_PROTECTED_BRANCH` (the PR's destination) "is the truth". That covers `develop` only
when it is the DESTINATION. A PR whose SOURCE is `develop`, `release/*` or `staging` produces a
truthful `review_status` row that passes `FixDispatch.whyNotPushable`, `FixTargets.isPushable()` and
the publisher floor, by design. No operator knob existed on 2026-09-04.

**Why:** round 1 of (global, m2-t5b-dispatch) raised it as M3 (CWE-284, MEDIUM) with a
`spire.factory.fix.never-push` glob-list remedy and the forge-side "is the branch protected" read as
the fuller fix. The lead's disposition decides whether it is accepted as a design residual.

**How to apply:** before re-raising on a later round, read the disposition in `.claude/reviews/` for
m2-t5b-dispatch. If accepted as residual, cite it as known rather than new. The same round also
flagged that `FixDispatch.plan` skipped `PushTarget.belongsTo` (ADR-040 §3's provider/repo leg) and
that V55's `from_fork` default becomes load-bearing once the saga wires `FixDispatch`.

Related: [[fix-command-actor-gate-design-position]]
