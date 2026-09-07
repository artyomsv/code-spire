# The runs list aggregates every run's charges to render one page of them

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Low |
| Location | `spire-orchestrator/.../factory/FactoryRunProjection.java` (`list`) |
| Found during | M2 task 8 review (the run↔review join reads), code-review lens |
| Date | 2026-09-04 |

## Issue

`GET /api/runs` joins each row to its cost through an **uncorrelated** grouped subquery:

```sql
LEFT JOIN (
      SELECT subject_id, SUM(cost_millicents), COUNT(*) FILTER (WHERE cost_millicents IS NULL), COUNT(*)
        FROM llm_charge
       WHERE subject_kind = 'RUN' AND archived_at IS NULL
       GROUP BY subject_id
) c ON c.subject_id = r.run_id
```

Nothing inside that subquery knows about the outer `LIMIT`, so it aggregates **every charge line of
every run ever executed** and then throws away all but the fifty rows the page asked for.

## Risks

Low today, and bounded rather than unbounded. V42's `llm_charge_subject_idx (subject_kind,
subject_id)` makes it an index scan over the `RUN` partition only, and a run produces one charge call
(`RunCharges.AGENT_CALL`) split into a handful of token-type lines. At current volumes it is not
measurable.

What makes it worth recording is that it grows with **total runs ever executed**, which is precisely
the quantity `MAX_RUN_PAGE` exists to keep off this page. The bound on the page does not bound the
work behind it, so the two protections do not compose — and the symptom, when it arrives, is a runs
list that gets slower for a reason nothing on the page suggests.

## Suggested Solutions

1. **`LEFT JOIN LATERAL`**, which restricts the aggregate to the rows the page actually returns:

   ```sql
   LEFT JOIN LATERAL (
         SELECT SUM(cost_millicents) AS priced_millicents,
                COUNT(*) FILTER (WHERE cost_millicents IS NULL) AS unpriced_lines,
                COUNT(*) AS line_count
           FROM llm_charge
          WHERE subject_kind = 'RUN' AND archived_at IS NULL AND subject_id = r.run_id
   ) c ON true
   ```

   Same three columns, same `costOf`, no change to any test. The one wrinkle is that a LATERAL with
   no matching rows yields a row of NULLs rather than no row — which `costOf` already handles, since
   that is exactly what the LEFT JOIN produces today for an uncharged run.
2. **Denormalise a `cost_millicents` and `cost_known` onto `factory_run`**, written when the charge
   lands. Removes the join entirely and makes the list read one table. Costs a migration, a second
   writer to a money-adjacent column, and the drift that comes with it — which is why it is second.
3. Leave it. Defensible while the factory runs tens of runs rather than tens of thousands, and the
   trigger is measurable: the page's latency tracking `SELECT count(*) FROM llm_charge WHERE
   subject_kind = 'RUN'` rather than the page size.
