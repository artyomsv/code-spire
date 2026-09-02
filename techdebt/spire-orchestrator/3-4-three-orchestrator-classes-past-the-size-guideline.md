# Three orchestrator classes are past the size guideline, for three different reasons

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Large |
| Location | `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (**2,503** lines), `.../pipeline/ResultSaga.java` (**598** lines), `.../attention/AttentionQueries.java` (**432** lines), `.../factory/FactoryRunProjection.java` (**316** lines), `.../factory/RunResource.java` (**302** lines) |
| Found during | ADR-023 LLM cost accounting — flagged by two task reviews and the whole-branch review. **Updated 2026-08-09 (ADR-025 spend caps)**, when `ResultSaga` and `AttentionQueries` were each flagged again, unprompted. |
| Date | 2026-08-07 (updated 2026-08-09) |

## Update — 2026-08-09, ADR-025 spend caps

Measured line counts, not estimates. Guideline is **300**.

| File | Before this branch (`9441ca0`) | Peak | Now |
|---|---|---|---|
| `ReviewProjection.java` | 1,847 | — | **1,867** |
| `ResultSaga.java` | 517 | **625** (`cb9886c`, the pre-spend gate) | **598** |
| `AttentionQueries.java` | 377 | — | **432** |
| `ConversationSaga.java` | 272 | — | **299** |

**The signal is not the counts.** It is that both files were flagged by the people adding to them,
who had each followed the file's own established pattern rather than breaking it. That is what
distinguishes a pattern which has outgrown itself from a series of individual mistakes — and the two
files have outgrown themselves in different ways, so they want different remedies.

### `ResultSaga` — the gates want their own collaborator

It gained the `refuse(...)` terminal transition, the `diffSizeDecision(...)` predicate and two gate
call sites. It peaked at 625 and came back to 598 when Task 7 extracted the spend comparison into
`SpendGate` — an extraction made for a **correctness** reason (the conversation gate had to reach the
same verdict from the same inputs, and two copies of a money comparison drift silently) that happened
to be the only thing on the branch that shrank the file.

The saga has grown a category of member it did not have before: *decisions about whether to proceed*,
distinct from the event handling that is its actual job. `SpendGate` is the first of them to get its
own home and shows the shape — one question, one answer type (`CapRefusal`), no saga state, callers in
two sagas plus `AttentionQueries`. `diffSizeDecision` is the obvious next: already a pure function of a
`DiffFetched` and `CapPolicy`, already returning `CapRefusal`, still in the saga only because it has
one caller today. `refuse(...)` is a weaker third candidate — it writes through `projection`,
`timeline` and `lifecycle`, so extracting it moves three collaborators rather than removing them.

So the direction is already established and the work is **finishing what the `SpendGate` extraction
started**: move the remaining gate decisions into the `caps` package, leaving the saga with the branch
structure and the emits. Cheap, and worth doing before anyone attempts (1) below.

### `AttentionQueries` — a pattern that scales linearly with the number of conditions

Different problem, and **not** a case of anyone doing it wrong. The file's established pattern is one
private method per condition — `llmProviderRows`, `scmProviderRows`, `reviewRows`, `credentialRows`,
`costRows`, `deadLetterRows`, and now `capRows` — eight such methods, each self-contained, each
correct. Only the two-variant `CostAttentionRow` earned its own file, because it needed per-row
acknowledgement wiring that the others do not.

Task 8 followed that convention exactly, which is why the growth is not attributable to it. The
pattern simply has no term that stops growing: every condition the panel learns costs the file another
method, so the class size is a direct function of how many things an operator can be told. It crossed
300 before this branch (377) and is now 432.

The remedy is therefore structural rather than a tidy-up: give each condition a small type behind a
common interface (`AttentionCondition` with one `evaluate` method), and let `AttentionQueries` become
the list of them plus the sort and the cap. `CostAttentionRow` is the precedent that already exists in
the package for a condition owning its own file — this generalises it instead of continuing to treat it
as the exception. Doing this per-condition also makes each one independently testable, which today
requires standing up the whole query set.

Deliberately *not* recommended: splitting by data source (SQL rows vs in-memory checks). `capRows` and
`deadLetterRows` take no `Connection` while the rest do, so that split looks natural and is the wrong
axis — it groups conditions by an implementation detail that a condition is free to change, and would
put two rows an operator sees side by side in different files.

## Update — 2026-09-02, M1 Task 4 (the run charge ledger)

**Two factory classes crossed the guideline in one commit**, and neither by much:

| File | Before | Now |
|---|---|---|
| `factory/RunResource.java` | 274 | **302** |
| `factory/FactoryRunProjection.java` | 294 | **316** |

Both were already within a few lines of 300, so the growth that crossed it is small: a
pre-dispatch pricing refusal (~28 lines) and a single read query (~22 lines). Recorded here rather
than refactored in the same commit, for the reason this entry already gives about `ReviewProjection`:
a structural move belongs on its own, and doing one inside a money-path change would hand a reviewer
a pure move mixed with a behaviour change.

Both have an obvious shape when someone does take them:

- `RunResource` has grown a category of member the sibling review resources solved differently —
  *refusals before dispatch*. There are now three (`machineAccount`'s two conflicts,
  `refuseAnUnpriceableModel`, `refuseOverTheSpendCap`), and `SpendGate` is the precedent for where
  they go: one question, one answer type, no resource state.
- `FactoryRunProjection` is repeating the read/write mixing this entry already diagnoses in
  `ReviewProjection`, three orders of magnitude earlier. `AttentionQueries` is the package's own
  precedent for splitting the reads out, and doing it at 316 lines costs almost nothing compared
  with doing it at 1,900.

The second point is the one worth acting on soon: the factory read model is at the size the review
read model was when splitting it would still have been cheap.

## Issue

*The original 2026-08-07 assessment, covering the first two files. Its line counts are the ones
measured then — see the Update above for current figures and for `AttentionQueries`.*

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
