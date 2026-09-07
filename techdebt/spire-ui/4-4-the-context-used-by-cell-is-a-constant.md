# The context sources "Used by" cell is a hardcoded constant, and nothing fails when it stops being true

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `spire-ui/src/components/SettingsContextProviders.tsx` (the Used-by cell in the row body) |
| Found during | Accounts and roles — PR #120 code-quality review (S9) |
| Date | 2026-09-07 |

## Issue

The Used-by column added on this branch renders the literal string `Reviewer · read` on every row.
It is not derived from anything: no field of `ContextProviderView` reaches it, and every row shows
the same two words whatever the source is.

That is **true today**. The review worker's context aggregator is the only consumer of a registered
context source, and it only reads. The column was added because an operator looking at a Jira or
Confluence registration could not tell who used it or with what rights, and the honest answer
happens to be one string.

It stops being true at M3. `docs/factory/MODULES.md` states the distinction the column is
displaying: "a context provider reads an issue as context; a work source also claims, comments and"
writes back — the difference is **rights, not transport**, and both arms reuse the same HTTP
clients against the same host. So a deployment can hold a work-source account for the very tracker
this table lists, and the cell will still say `read` beside it (ADR-035).

**Nothing fails at that point.** The cell is a constant, so no type changes, no test breaks, and
`SettingsContextProviders.usedby.test.tsx` keeps passing — it asserts the literal on every row,
which is exactly what a stale constant continues to do. The screen simply reports a narrower right
than the deployment has granted, on the screen an operator consults to find out what an account can
do.

## Risks

Low, and confined to what the operator is told:

- **A wrong answer about rights reads as a checked one.** The column's whole purpose is to say what
  an account may do with a host. A constant that under-reports write access is the failure mode
  worth avoiding, because it invites the assumption that a tracker credential is read-only when it
  is not.
- **No test would announce the drift.** The guard asserts the constant, so it is satisfied by the
  bug. This is the shape `CLAUDE.md` records under "mutation-verify every guard": a test that passes
  for the same reason the defect exists.

Not Medium: the cell grants no rights and gates nothing. It is a label.

## Suggested Solutions

1. **Derive it from a per-source consumer list once the backend exposes one** (the fix). The
   orchestrator already knows which roles hold an account for a host — that is what
   `GET /api/providers/serving` answers for the forge side, and the Repositories screen renders it
   per role. The context table wants the same shape: a list of `(consumer, rights)` on
   `ContextProviderView`, rendered as chips, with `Reviewer · read` becoming one possible value
   rather than the only one. Do it in the M3 commit that first registers a work source, so the
   column and the capability arrive together.
2. **Until then, make the constant defend itself.** A test asserting that no work-source account
   exists in the schema would fail the day M3 adds one, which turns a silent drift into a build
   failure naming this file. Cheaper than (1) and it buys the reminder without the backend field.
3. **Drop the column.** Defensible if the answer is going to be constant for another milestone, but
   it removes the one thing on the screen that distinguishes a read credential from a write one, so
   prefer (2).
