# `llm_charge.credential_ref` is added by V42 and written by nothing

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-orchestrator/src/main/resources/db/migration/V42__llm_charge_run_subject.sql` (adds the column), `spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java` (`bindChargeLine` binds twelve columns; this is not one of them) |
| Found during | M1 Task 4 four-lens review, round 1 (rules-compliance and code-reviewer, independently) |
| Date | 2026-09-02 |

## Issue

`V42` added three ledger facts at once, and its own comments say why each had to arrive then rather
than later. Task 4 closed two of them — `subject_kind = 'RUN'` and `capability` — and its debt entry
was deleted on the strength of that. The third was not done, and deleting the entry took the only
record of it with it. This entry restores that record, scoped to what actually remains.

```sql
-- Which pool member paid, so an UNMETERED run is still attributable to a credential.
ALTER TABLE llm_charge ADD COLUMN credential_ref TEXT;
```

`git grep credential_ref` finds the DDL, the design docs and nothing else. Every row lands NULL, so
the capability the migration comment asserts does not exist.

**The reasoning Task 4 used to promote `capability` applies here verbatim**, which is why this is
worth its own entry rather than a note: `docs/factory/ARCHITECTURE.md` records that `capability` and
`credential_ref` join in the same migration *because they cannot be backfilled*, and
`docs/factory/PACKAGING.md` gives the purpose — *which pool member paid, so an unmetered run is still
attributable*. Under that argument no later migration can repair the rows written meanwhile.

It differs from the `capability` defect in one way, and it is the way that made it easier to miss: a
nullable column fails quietly, where a `NOT NULL DEFAULT 'REVIEW'` column fails by asserting
something false.

## Risks

- Every charge written from now on is permanently unattributable to the credential that paid for it,
  on both the review and the run path.
- FR-F12's credential pool (Task 10) is the feature that makes this matter: with several harness
  credentials in rotation, "which key spent this" has no answer, and an operator investigating an
  unexpected bill on one key cannot separate it from the others.
- An `UNMETERED` deployment has no money axis at all, so attribution by credential is the only
  per-payer signal the ledger could offer.

## Suggested Solutions

- Carry the paying credential on `ChargeCall` and bind it. **Both paths, or neither** — populating it
  for runs alone produces a column that is filled for half the rows, which reads as done and is
  worse than uniformly NULL.
- The run path knows its provider at dispatch (`RunResource` resolves an `LlmProviderConfig` before
  building the command) but does not persist it: `factory_run` has no provider column, so this needs
  a migration and a read alongside `modelOf`. The review path resolves its credential per command in
  `ResultSaga`; that one is already in scope at the charge site.
- Task 10 is the natural owner — it is the task that makes the column mean something, and it will be
  editing the credential resolution path anyway.

## Progress — M1 Task 10 (2026-09-03)

**The RUN half is closed.** `harness_credential` gives a run a per-key identity for the first time,
`factory_run.harness_credential_id` records which member it was dispatched with, and `RunCharges`
binds it into `llm_charge.credential_ref`. V42 added that column for exactly this and nothing had
ever written it, because until the pool there was no per-run credential to write.

**The REVIEW half is open, and this entry deliberately shipped half of what it asked for.** Its own
Suggested Solutions said "both paths, or neither — populating it for runs alone produces a column
that is filled for half the rows, which reads as done and is worse than uniformly NULL." That
objection is answered rather than ignored: `V52` documents the split on the column itself, so a
NULL now means "a review call, or a run from before the pool" rather than "nobody got round to it".
A half-filled column with a written-down rule is not the ambiguity the objection was about.

**And the entry's premise for the review half was wrong.** It claimed the credential is "already in
scope at the charge site". A review found otherwise: `ResultSaga.charge` works from `ReviewGenerated`
usage and has no credential in hand at all. So the review half is not a one-line addition — it needs
the resolved provider carried to the charge site, which is a change to the review pipeline and not
to the factory.

### Remaining

- Carry the resolved LLM provider id from `ResultSaga`'s credential brokering to the charge site, so
  a review call names the key that paid for it too.
- Note that the reviewer's key and the factory's pool are now different registries entirely, so the
  two attributions will never be comparable ids — which is correct, and the point of ADR-038's
  reasoning applied to the model side.
