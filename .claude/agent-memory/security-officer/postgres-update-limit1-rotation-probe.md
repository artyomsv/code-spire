---
name: postgres-update-limit1-rotation-probe
description: A pool "select and stamp in one statement" (UPDATE ... WHERE id = (SELECT ... ORDER BY ... LIMIT 1) RETURNING) hands the SAME row to two concurrent callers on Postgres; only FOR UPDATE SKIP LOCKED spreads them. Two-session probe recipe.
metadata:
  type: project
---

`UPDATE t SET last_used_at = now() WHERE id = (SELECT id FROM t ... ORDER BY ... LIMIT 1) RETURNING id`
does NOT rotate under concurrency on Postgres (verified on 18-alpine, 2026-09-03, for the
`harness_credential` pool in M1 Task 10 / FR-F12): the uncorrelated subquery is an InitPlan
evaluated once, so the second session blocks on the first's row lock, re-checks `id = <same id>`
after commit, and updates the same row. Both dispatches got member A. Adding
`FOR UPDATE SKIP LOCKED` inside the subquery gave A and B different members.

**Why:** the class javadoc claimed the single statement is what stops "two dispatches a millisecond
apart both taking the same longest-rested member". Reasoning from the docstring would have passed
it; the probe falsified it in under a minute.

**How to apply:** any review of a "claim one row" statement (credential pool, work queue, lease).
Probe rather than argue:

```
docker run -d --name secprobe-pg -e POSTGRES_PASSWORD=TEST-probe -e POSTGRES_DB=probe postgres:18-alpine
P="docker exec -i secprobe-pg psql -v ON_ERROR_STOP=1 -U postgres -d probe -Atq"
# create table + 2 rows via $P <<'SQL' ... SQL
( echo "BEGIN; $SEL; SELECT pg_sleep(4); COMMIT;" | $P | sed 's/^/A: /' ) &
sleep 1.5; echo "$SEL;" | $P | sed 's/^/B: /'; wait
docker rm -f secprobe-pg
```
Same ids => no rotation. No `-v` mount needed, so the MSYS path trap does not apply.

Related: [[postgres-constraint-name-probe]], [[semgrep-on-windows-git-bash]]

A second lesson from the same round: a rotation-on-failure control whose trigger is a wire
value (`CREDENTIAL_REJECTED`) must be checked for a PRODUCER — `git grep <value> HEAD -- '*/src/main/*'`
across harness, worker and publisher modules. The saga-level tests injected the string and all
passed while nothing in the system could emit it.
