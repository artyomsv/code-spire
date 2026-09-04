---
name: test-fixture-cleanup-keys
description: Test @BeforeEach cleanups that DELETE by an id prefix are safe only while that id is a literal the fixture writes; run ids are derived by RunIds and review ids are literal today but will not stay so
metadata:
  type: project
---

`@BeforeEach` cleanups across `spire-orchestrator` tests delete by `WHERE <id> LIKE 'TEST-...%'`.
That is safe only when the id is a **literal the fixture supplies**. It breaks silently when the id
is **derived by a builder**, because the builder decides the leading characters.

- `factory_run.run_id` is derived by `RunIds.of`, which spells the platform first
  (`run::github:WS/REPO:subject:attempt`). No `run::TEST-%` predicate can match a realistic id.
  `FixRunsTest` hit this: rows leaked between cases and six previously-green assertions went red the
  moment a realistic id appeared. Fixed by keying on `WHERE workspace = 'TEST-WS'`.
- `review_id` is **not** derived that way — `ReviewIds` carries no provider, which its own javadoc
  calls a tracked defect. So `review::TEST-%` genuinely matches today.

**Why:** five fixtures depend on that second fact — `FixTargetsTest`, `FindingProjectionTest`,
`AnalyticsQueriesTest`, `PreferenceScanTest`, `FindingBackfillTest`. The day `review_id` gains the
platform prefix `RunIds` already has (named as intended work in `RunIds`'s javadoc), all five stop
matching and start leaking rows between cases — the same failure, five times, far from the change
that caused it.

**How to apply:** in any review touching `ReviewIds` or a review-id format, flag those five fixtures.
When reviewing a new fixture, the rule is: a cleanup predicate keys on a column the fixture sets
literally (workspace, scope, subject), never on a prefix of an id something else derives.

Related: [[orchestrator-fakes-are-argument-blind]].
