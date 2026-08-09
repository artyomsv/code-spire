# The read model and the result saga have grown into a god-class pair

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Large |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (~1,740 lines), `.../pipeline/ResultSaga.java` (~470 lines) |
| Found during | ADR-023 LLM cost accounting — flagged by two task reviews and the whole-branch review |
| Date | 2026-08-07 |

## Issue

The project's guideline is **300 lines per class**. `ReviewProjection` is roughly **1,740** and `ResultSaga`
roughly **470**. Neither was created by ADR-023 — that branch added about 80 lines to the first and 46 to
the second — but three separate reviews raised it independently, which is the signal worth recording.

They are one problem rather than two. `ResultSaga` is the only substantial writer to `ReviewProjection`, and
almost every field the projection holds exists because a saga branch puts it there. So the projection is
large *because* the saga is broad: every integration event the saga handles needs somewhere to land, and
that somewhere is one class holding the review header, the findings, the reconciliation verdicts, the
conversation threads, the timeline, the attention acknowledgement, the retry schedule, the prior-run
snapshot and now the charge ledger.

`ReviewProjection` also mixes three distinct responsibilities that happen to share a table set: **writing**
projections from events, **reading** them for the REST API, and **rendering** them into view records
(`ReviewDetail`, `ReviewSummary`, `ChargeLineView`). The read and render halves are why it has grown fastest
— every new API field adds a query, a mapping and a view component to the same file.

## Risks

Not correctness, today. The class is well-tested and its methods are individually small and readable. The
cost is concentrated in three places:

- **Review attention is finite per file.** ADR-023's Critical 1 — the detail payload never carrying
  `unpricedCalls`, so a partial cost total rendered as a confirmed `$0.000` — lived in `toDetail`, in this
  class. Eleven task reviews and nine fix rounds did not find it; a whole-branch review reading the *path*
  rather than the *file* did. A 1,700-line file is one where a missing constructor argument does not
  look like anything.
- **Merge conflicts scale with breadth.** Any feature touching reviews touches this file, so parallel work
  serialises on it.
- **The read/render mixing means an API change is a write-path change.** Adding a field to `ReviewDetail`
  means editing the class that also owns the event-driven writes, so a reviewer of an API change is handed
  the write path too.

Medium rather than Low because of the first point specifically: the branch has direct evidence that a defect
hid in this file's size.

## Suggested Solutions

1. **Split by responsibility, not by table** (the shape the code is already asking for): a writer
   (`ReviewProjectionWriter` — the `record*`/`update*`/`append*` methods the saga calls), a reader
   (`ReviewQueries` — the SQL that answers the REST surface), and a mapper (`ReviewViews` — row → view
   record). The three have almost no shared state beyond the `DataSource` and the encryption service. Do it
   as its own commit with no behaviour change, so the diff is reviewable as a pure move.
2. **Narrow `ResultSaga` first instead.** Each `case` branch is nearly independent; extracting the
   conversation and reconciliation branches into their own handlers would shrink both classes, since much of
   the projection's surface exists for those branches alone. Cheaper than (1) and it reduces the pressure
   that grew the projection.
3. **Leave it and add a size check.** Defensible only if paired with something that stops further growth —
   the repository already has the idiom in `spire-arch` (`PureModulesAreFrameworkFreeTest`,
   `RedirectHandlingHasOneHomeTest`). A test asserting these two files do not *grow* would at least make the
   next addition a deliberate decision rather than an unremarked one.

Do not attempt (1) as part of a feature branch. It is a large, purely structural change and belongs alone.
