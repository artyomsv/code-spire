---
name: v51-do-block-silent-on-zero-match
description: V51's DO block finds the unnamed ended_at CHECK by definition, but SELECT ... INTO without STRICT is silent on both zero and multiple matches - the same silence it was written to avoid
metadata:
  type: project
---

`V51__run_dispatch_uncertain.sql` drops V43's unnamed `ended_at` CHECK by searching
`pg_get_constraintdef(oid) LIKE '%ended_at%'` in a `DO $$` block, explicitly to avoid
`DROP ... IF EXISTS` on a guessed name — because "a wrong guess succeeds silently and the first
uncertain dispatch then fails to write."

**The replacement has the same silence.** PL/pgSQL `SELECT ... INTO` (no `STRICT`) sets the
variable to NULL on zero rows and takes an arbitrary first row on many — neither raises.

Verified 2026-09-03 against real Postgres 18.4 (throwaway container, V1..V51 applied in order):
- Predicate matches **exactly one** constraint (`factory_run_check3`) on the shipped V1..V50
  state, so the migration is correct as shipped.
- With the predicate neutered to match zero, V51 applies with **exit 0 and no message**, leaves
  `factory_run_check3` in place, and the first `dispatch_uncertain` insert fails with
  `violates check constraint "factory_run_check3"`.
- With a second constraint mentioning `ended_at` present, V51 drops an arbitrary one and leaves
  the other — order-dependent, no `ORDER BY`.

**How to apply:** `SELECT conname INTO STRICT old_name` turns both silent cases into a loud
migration failure. Raise it when reviewing any future migration that locates a constraint by
definition rather than by name. Related: [[kafkasends-seam-untested]].
