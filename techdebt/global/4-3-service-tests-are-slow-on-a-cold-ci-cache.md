# The service-tier test job is slow on a cold CI cache

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Small |
| Location | `.github/workflows/ci.yml`, job `service tests + packaging` |
| Found during | The repository's first CI runs, 2026-08-05 |
| Date | 2026-08-05 |

## Issue

**This entry replaces an earlier one that overstated the problem, and the correction is the point.**

The first CI run measured the service-tier job at over 40 minutes and it was still going when
observation stopped. That was filed as Medium criticality on the assumption that Quarkus Dev Services
would pay the same cost every run.

The very next run, with the Gradle cache warm, completed the whole job — `testServices` plus
`assemble` — in **2 minutes 48 seconds**. For comparison, `fast tests` took 44s and `dashboard` 58s.

So the cost is **cold-cache, not per-run**. The first run downloaded the entire Gradle dependency graph
and pulled every Dev Services image from scratch; `gradle/actions/setup-gradle` then cached the former
and the runner image already carries much of the latter.

The residual issue is real but small: GitHub evicts caches after 7 days of no access, and any change to
the cache key restores the cold path. So an occasional run will be slow, and that run is bounded by the
job's `timeout-minutes: 60` rather than the six-hour default.

## Risks

Low. A slow run is occasional, self-limiting and does not block correctness. The failure mode is a
contributor waiting once after a quiet week, not a permanently unusable gate.

The larger risk this entry exists to record is **the reasoning error**: one cold measurement was
generalised into a property of the system, and a Medium-criticality debt item was filed on it. The
mitigation list that came with it — including "move the job off the PR path" — would have weakened a
working gate to solve a problem that mostly does not exist.

## Suggested Solutions

Only worth acting on if cold runs become frequent:

1. **Leave it.** 2m48s warm is well inside budget, and the timeout bounds the cold case. This is the
   current position.
2. **Pre-pull the Dev Services images** in the job so a cold run only pays the Gradle download.
   Determine the exact images from the Quarkus config rather than guessing tags.
3. **Warm the cache on a schedule** — a weekly run on `master` keeps the Gradle cache from being
   evicted, so the cold path is hit rarely.

Do not move `testServices` off the pull-request path on the strength of the original measurement.

## Do not reach for parallel test execution here (added 2026-08-07, ADR-023)

Speeding this job up with `maxParallelForks` in `spire-orchestrator/build.gradle.kts` — the obvious first
idea, and the reason this warning lives in this file rather than a new one — **would break the module's
test suite**, in a way that passes locally and fails intermittently in CI.

`LlmModelRegistry.delete(...)` refuses to remove an `llm_model` row that any `llm_provider` row names
(ADR-023's catalog guard). Several `@QuarkusTest` classes create providers and catalogued models in
`@BeforeEach` with no `@AfterEach`, and they share one Quarkus application and one Dev Services Postgres.
`LlmModelResourceTest.clean()` is the only caller of `delete`, and it is safe **only** because it deletes
every provider before any model, and because the module currently runs strictly sequentially — no
`maxParallelForks` is set and no `junit-platform.properties` exists.

Under parallel forks, another class's `@BeforeEach` can create a provider **between** `clean()`'s two
deletion steps. The model delete then throws `IllegalStateException("Model 'X' is in use by 1 LLM
provider(s)")` and the whole class errors out — with a frequency that depends on timing, so it will look
like flakiness rather than a design constraint.

If parallelism here is ever genuinely needed, give the affected classes their own `@TestProfile` with an
isolated datasource, or add `@AfterEach` cleanup, **before** raising the fork count. Do not treat the
resulting failures as flaky tests to retry.
