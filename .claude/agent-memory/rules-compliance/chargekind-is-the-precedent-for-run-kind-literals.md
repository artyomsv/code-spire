---
name: chargekind-is-the-precedent-for-run-kind-literals
description: ChargeKind enum already models BUILD/FIX with a written rationale — cite it when flagging "BUILD"/"FIX" string literals in factory projection writers
metadata:
  type: project
---

`spire-orchestrator/src/main/java/dev/codespire/orchestrator/llm/ChargeKind.java` is an enum with
`REVIEW`, `RECONCILE`, `FOLLOWUP`, `BUILD`, `FIX` members, and its class javadoc states the exact
rationale for preferring an enum over a string here: *"the ledger's `kind` CHECK lists these names
verbatim: a typo'd literal would otherwise pass compilation and fail the INSERT at runtime."*

**Why:** it turns "extract magic strings" from a generic style rule into an in-repo precedent with
the project's own reasoning attached, one module away from the code that deviates. V54's own comment
independently admits the risk — *"two independent literals in two files, with nothing enforcing that
they stay agreed"* — so the migration author and the enum author already agree; only the projection
writer does not.

**How to apply:** whenever a factory writer spells `"BUILD"` or `"FIX"` as a literal (M2 introduced
two in `FactoryRunProjection`'s `QueuedRun` convenience constructor and `asFixFor`), cite ChargeKind
as the pattern rather than arguing the rule abstractly. The closed set the DB enforces is
`factory_run_kind_closed CHECK (kind IN ('BUILD','FIX','SPEC','PLAN'))` — wider than ChargeKind's
factory half, so a shared `RunKind` enum needs `SPEC` and `PLAN` too, and is not simply a rename of
ChargeKind. Related: [[no-project-rules-dir-and-no-lint-gate]] — nothing lints this, so it only gets
caught by review.
