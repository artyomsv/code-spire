# A migration's row rewrites are verified by hand, by nothing repeatable

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-orchestrator/src/main/resources/db/migration/V46__run_failure_cause.sql` (two `UPDATE`s), `V47__run_delivered_nothing.sql` (one `UPDATE`) |
| Found during | M1 Task 1 four-lens review (QA F9, security L4) |
| Date | 2026-09-02 |

## Issue

Three `UPDATE` statements translate rows written under an older vocabulary: two in `V46` mapping
legacy cause spellings and sweeping the remainder to `UNCLASSIFIED`, one in `V47` relabelling a
`succeeded` run that pushed nothing.

**No automated test exercises any of them.** On a fresh database every one matches zero rows, which
is the only state the suites ever see. Both the QA and security lenses ran them by hand against a
throwaway Postgres 18.4 and confirmed each does what it says — a legacy spelling translates, a
junk value becomes `UNCLASSIFIED` with its word preserved in the detail, a legitimate value is left
alone, and a legacy empty run is relabelled. That is real evidence and it is not repeatable evidence.

This matters more than a normal coverage gap because the reason `V46` needed rewriting during review
was precisely a row-rewrite defect: its first version sent eleven translatable values to
`UNCLASSIFIED`, discarding the classification of every failure the deployment had recorded. Nothing
in the suite noticed, because nothing runs against a database with history in it.

## Risks

- A future migration's backfill is wrong in the same way and ships, since the tier that would catch
  it only ever sees an empty table.
- The failure is silent and one-directional: rows are already rewritten by the time anyone looks.

## Suggested Solutions

- A migration test that seeds a table at an earlier schema version, runs the migration, and asserts
  the rows. Flyway supports targeting a version, so a test can migrate to `V45`, insert rows in the
  old vocabulary, then migrate to `latest` and assert. This is the shape that generalises.
- Cheaper and narrower: assert the alias `CASE` in `V46` covers exactly `RunFailureCause`'s alias
  keys by reading both as text, so the migration and the runtime translation cannot disagree even
  though neither is executed against data.
