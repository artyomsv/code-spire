---
name: postgres-constraint-name-probe
description: How to verify a migration that DROPs an auto-named Postgres constraint (the naming rule, and a throwaway-container probe that takes one minute)
metadata:
  type: project
---

This repo's migrations sometimes drop a constraint that an earlier migration declared unnamed
(V44 on `scm_provider`, V47 on `factory_run`). `DROP CONSTRAINT IF EXISTS <guess>` on a wrong
guess passes silently, so verify the guess rather than reason about it.

**Naming rule (verified on Postgres 18, 2026-09-02):** a CHECK that references exactly one column
is named `{table}_{column}_check`; a CHECK over two or more columns is `{table}_check`, then
`{table}_check1`, `_check2`, ... in declaration order. Column-level `CHECK (attempt >= 1)` is
`{table}_attempt_check`. NOT NULL now also shows as `{table}_{column}_not_null` rows in
`pg_constraint`.

**Probe (no mount needed, avoids the MSYS path trap):**

```
docker run -d --name secprobe-pg -e POSTGRES_PASSWORD=TEST-probe -e POSTGRES_DB=probe postgres:18-alpine
# wait for pg_isready, then pipe the migrations in order via stdin:
git show HEAD:<migration.sql> | docker exec -i secprobe-pg psql -v ON_ERROR_STOP=1 -U postgres -d probe -q
docker exec secprobe-pg psql -U postgres -d probe -Atc \
  "SELECT conname||' => '||pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='<table>'::regclass ORDER BY conname"
docker rm -f secprobe-pg
```

Seed a `TEST-` row before the migration under review to see what its UPDATEs do to real-shaped
data; that is how V46's alias-less rewrite to `UNCLASSIFIED` was caught.

**Why:** the lead asked directly whether V47's name guess was right; the answer needed evidence,
not recall. The repo's own V44 already has the robust pattern (look the constraint up by
`pg_get_constraintdef`, `RAISE` when absent) — cite it when a later migration guesses a name.

**How to apply:** any review touching `db/migration/*.sql` that drops or replaces a constraint,
or rewrites rows in an UPDATE.

Related: [[semgrep-on-windows-git-bash]]
