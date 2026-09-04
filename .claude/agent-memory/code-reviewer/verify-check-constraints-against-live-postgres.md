---
name: verify-check-constraints-against-live-postgres
description: How to falsify a "this schema constraint makes X unrepresentable" claim without running Gradle or writing a row — a live dev Postgres plus a VALUES-only SELECT
metadata:
  type: reference
---

Migration and javadoc comments in this repo routinely claim a CHECK constraint makes some state
unrepresentable, and the review contract forbids running Gradle. Verify the claim directly:

```bash
docker exec spire-postgres psql -U spire -d spire -t -A -F'|' -c "
SELECT kind, review_id, finding_ref,
       ((kind = 'FIX') = (review_id IS NOT NULL AND finding_ref IS NOT NULL)) AS check_passes
FROM (VALUES ('FIX','r1','f1'), ('BUILD','r1',NULL), ('BUILD',NULL,'f1')) AS t(kind,review_id,finding_ref);"
```

The container is `spire-postgres` (dev, host :39200); user and database are both `spire`. Other
Postgres containers on this machine (`deploy-db-1` :34432, `spire-e2e-postgres-1`, `ledger-db`) are
different stacks — `-U postgres` fails on all of them.

**Why:** a CHECK is a pure boolean expression, so a `FROM (VALUES ...)` table paints the whole truth
table in one read-only query. No table is touched, no row is written, nothing violates the read-only
review contract or the no-synthetic-data rule, and the output is evidence rather than reasoning.

**How to apply:** whenever a comment or a test javadoc asserts "the constraint makes this
unrepresentable", "no fixture can build that row", or "these two columns cannot disagree". Watch
especially for `(A) = (B AND C)` shapes: they only forbid the case where B and C are *both* set
against A, so `NOT A` with B alone, or C alone, stays legal — which is how a per-review count filter
can turn out to be load-bearing after being documented as belt-and-braces. Relates to
[[mutation-claims-in-review-prompts-are-hypotheses-to-test]].
