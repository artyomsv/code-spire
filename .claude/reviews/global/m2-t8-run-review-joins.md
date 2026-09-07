# Code Review State: global / m2-t8-run-review-joins

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over the T8 commits on `feat/factory-m2-deliver` (PR #119): `GET /api/runs`, the run↔review
join, `RunCost`, and the fix-key pin. Fixes in `d8f2c31`.

**One lens, deliberately.** T6+T7's round ran all four; this slice is a read model and an endpoint in
one module, and code-reviewer is the lens that fits it. Security's surface here is the role gate,
which the diff's own tests cover and which a mutation confirmed; rules-compliance's is unchanged from
the previous round. **qa did not run** — it exhausted its session limit during the T6+T7 round and its
questions there are still open.

**No production defect was found.** Both hazards I asked about specifically — the `wasNull`
sequencing and whether the LEFT JOIN could duplicate rows — were confirmed sound. What the round
found was two tests of mine guarding almost nothing.

## Resolved (fixed in code; do not re-raise)

- [code-quality/IMPORTANT-1] `theRowIdDoesNotSurviveARoundButTheThreadRefDoes` **could not fail**.
  `FIND_BY_THREAD` is `ORDER BY id DESC LIMIT 1` over a monotonic serial, so a higher id after a
  second insert is true by construction — deleting `deleteRound` from `recordGenerated` entirely
  left it passing. It measured the sequence, not the replacement. Now asserts the OLD row is gone and
  exactly one remains. Its comment also said "round two" while passing `round = 1`; the code was the
  honest half. — round 1
- [code-quality/IMPORTANT-2] The status derivation had **both** halves wrong in the silent direction.
  `NOT_A_STATUS` was unreachable (the entry's value already failed the shape filter beside it) while
  the javadoc called it the thing keeping the derivation honest; and the shape filter itself would
  have dropped a status spelled with a digit, hyphen or capital, leaving the derived set equal to a
  `STATUSES` that also omitted it — green about a status nobody can filter for. — round 1
- [code-quality/IMPORTANT-3] `STATUSES` was checked against Java and never against the schema. Both
  halves could agree while a migration added a tenth value to `factory_run_status_closed`. The test
  now reads that CHECK, with a second case asserting the constraint is found by name and that the
  lookup answers empty for a name that is not there — so a rename cannot leave it comparing nothing
  to nothing. **The reflection was deleted rather than fixed**: removing its shape filter made it
  sweep up the class's SQL constants, needing an allowlist that grows with every query. — round 1
- [code-quality/IMPORTANT-4] The cost subquery ignored `archived_at`, unlike all four neighbouring
  `llm_charge` reads. Latent — nothing writes the column today — but the day purge lands this page
  would have totalled lines every other cost surface excludes. — round 1
- [code-quality/IMPORTANT-5] `costOf` read `wasNull()` inside a short-circuit expression, correct
  only because it sat to the LEFT of another `getLong`. Any reordering for readability would have
  broken it silently. Now read into a local immediately. — round 1
- [code-quality/#6] Two of `costOf`'s unknown branches are unreachable through this query. Kept and
  LABELLED as defensive, with the SQL invariant that makes them so — rather than left looking like
  tested paths. — round 1
- [code-quality/#7] `r.pushed_as` was selected and never read. — round 1
- [code-quality/#8] `RunCost` had no identity for `plus`. `unknown()` is an ABSORBING element, so the
  obvious fold seeded with it answers unknown for every input, including a list where every cost is
  known — a footer reading "cost unknown" with nothing looking wrong. `zero()` added and named for
  exactly that, with the case that proves the wrong seed is wrong. Also `Math.addExact`, so an
  overflow cannot be reported as "a run cannot cost less than nothing". — round 1
- [code-quality/#9] `limit` is parsed from a `String`. As an `Integer` query parameter a failed
  conversion is mapped to **404** by JAX-RS, so `?limit=abc` answered "there is no such endpoint"
  about an endpoint that exists. — round 1
- [code-quality/#10] `?kind=fix` worked and `?status=QUEUED` was a 400 — two case conventions in one
  query string. Both fold now. — round 1
- [code-quality/#11] `RunFilter`'s javadoc claimed a record removes the transposition hazard. A
  canonical constructor is positional, so it MOVES it. Corrected to name what actually polices it
  (`eachFilterNarrowsRatherThanAnsweringEverything`). The ordering javadoc also justified the
  tiebreak by paging that does not exist; determinism is justification enough. — round 1
- [code-quality/#13] Page-size constants moved beside their siblings; `Arrays` imported rather than
  fully qualified; `assertNull` over `assertEquals(null, …)`. — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [code-quality/#11-alt] Replacing the parallel `sql`/`bound` lists with a `Clause` record. The
  reviewer's own verdict was "correct as written, I would not block on it" — each `append` is
  immediately followed by its `add` with no branch between, and `WHERE 1 = 1` removes the
  first-clause special case. Revisit when a fourth filter arrives. (round 1)
- [code-quality/#9-alt] Changing the TRANSCRIPT endpoint's clamp-vs-refuse posture to match the runs
  endpoint's. Real inconsistency, correctly identified — but changing a shipped endpoint's behaviour
  for symmetry belongs in its own change, not in a slice that adds a different endpoint. (round 1)
- [code-quality/#13-alt] Renaming `DispatchRequestParser.badRequest` now that a list endpoint uses
  it. Fair, and it is a rename touching several call sites; carried rather than done here. (round 1)

## Carried to a later round

- **`techdebt/spire-orchestrator/4-3-the-runs-cost-subquery-aggregates-every-run.md`** — the grouped
  subquery is uncorrelated, so it aggregates every `RUN` charge line before the join regardless of
  `LIMIT`. Bounded by V42's `(subject_kind, subject_id)` index today; grows with total runs ever
  executed, which is what `MAX_RUN_PAGE` exists to bound. `LEFT JOIN LATERAL` restricts it to the
  page when it matters.
- **`RunResourceTest` never cleans `factory_run`**, so `registeredRun()` rows accumulate for the life
  of the database. Its list assertions survive only because `ORDER BY started_at DESC` puts each new
  row at the front of a 500-row page — a property nothing states and nothing protects.
  (`FactoryRunListTest.clean()` does it correctly.)
- **qa's section**, still unrun from the T6+T7 round.

## Verification

`:spire-orchestrator:test` — 1178 tests, 0 failures.

Eleven mutations across the slice, each killing exactly its intended test: newest-first dropped, the
limit binding ignored, the unpriced-line count dropped, zero-for-no-charge, an unknown status
accepted, the role widened to `@PermitAll`, a total that ignores unknown members, `zero()` made
unknown, the limit parse reverted to a 404, and the status case-fold removed.

**One of those earned its keep by exposing a weak test.** Widening the endpoint to `@PermitAll` left
every case green — including the anonymous one, because that 401 comes from the deployment's auth
policy before any annotation is consulted. Only an authenticated caller holding neither role can tell
the annotation apart from the wall behind it.
