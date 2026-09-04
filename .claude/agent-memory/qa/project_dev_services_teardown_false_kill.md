---
name: dev-services-teardown-false-kill
description: A FlywaySqlUnableToConnectToDbException / ConnectException red with most tests SKIPPED is Quarkus Dev Services teardown between back-to-back invocations, not a mutation kill — re-run before recording it
metadata:
  type: project
---

Back-to-back filtered `:spire-orchestrator:test` invocations (the mutation loop) can produce a red
that looks exactly like a kill but is infrastructure. The signature, measured 2026-09-04 on
`feat/factory-m2-deliver`:

```
16 tests completed, 1 failed, 15 skipped
FixRunsTest > <some test> FAILED
    Caused by: FlywaySqlUnableToConnectToDbException at JdbcUtils.java:78
      Caused by: org.postgresql.util.PSQLException -> java.net.ConnectException
```

The previous invocation's log ended with `Removed 2 Quarkus Dev Services container(s).`, so the next
one raced its own Postgres coming up. The failing test NAME is arbitrary — whichever ran first — and
in the observed case it named a test that does not even touch the mutated code.

**Why:** a mutation report that counts this as KILLED credits a test that never executed, which is
worse than a missed survivor: it certifies coverage that is not there. Re-running the identical
mutant produced `16 tests completed, 1 failed` with **0 skipped** and a different, correct test
failing at the right line.

**How to apply:** for every @QuarkusTest mutation run, read `N tests completed, N failed, **N
skipped**` before recording a verdict. Non-zero skipped, or a stack whose root cause is
`ConnectException` / `ContainerLaunchException` rather than `AssertionFailedError` or
`PSQLException` at the fixture line, means re-run. A real kill names a line in the test file
(`FixRunsTest.java:67`) and skips nothing. Sibling signature at full-suite scale:
[[orchestrator-docker-contention-signature]].
