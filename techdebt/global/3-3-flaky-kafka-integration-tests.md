# Kafka integration tests fail in the full suite but pass in isolation

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-gateway/src/test/java/dev/codespire/gateway/GitHubWebhookTest.java` (`signedWebhookLandsKeyedAndTypedOnIntegrationTopic`), `GitLabWebhookTest.java` (`tokenedWebhookLandsKeyedAndTypedOnIntegrationTopic`), `spire-orchestrator/src/test/java/dev/codespire/orchestrator/OrchestratorChoreographyTest.java` (`choreographyCompletesAndStaleResultsAreDropped`, and on some runs `replyInOwnedThreadYieldsAnswerFollowUp`) |
| Found during | Merging `scm-parity-gitlab-bitbucket` — the flakes masked a real test failure and made "is the suite green?" unanswerable without three runs and a master comparison |
| Date | 2026-07-25 |

## Issue

`./gradlew test` fails on a rotating subset of the Kafka-backed integration tests while every one of
them passes when run in isolation (`--tests "*GitHubWebhookTest"` etc.). Measured on 2026-07-25:

| Run | Failures |
|---|---|
| Full suite, run 1 | `FollowUpPromptTest` (a REAL failure, since fixed) + `choreographyCompletes…` |
| Full suite, run 2 | `signedWebhookLands…`, `tokenedWebhookLands…`, `choreographyCompletes…` |
| Full suite, run 3 | same three as run 2 |
| Each suite in isolation | all green |
| Same suites on `master` (pre-merge) | the two gateway tests **plus four** orchestrator tests |

The choreography assertion failure names the cause directly: it expected command #1 to be `FetchDiff`
and instead read an `AnswerFollowUp` **belonging to a different test's fixture**
(`review::convo-ws/convo-repo#5`). So records produced by one test are visible to another's consumer —
tests share a topic (and a consumer group / offset position) rather than being isolated per test.

The assertions themselves are "the record lands on the topic" checks, which are exactly the ones that
break when another test's records interleave or when a consumer starts from a position that already
contains someone else's traffic.

## Risks

- **A real regression can hide inside the noise.** This is not hypothetical: run 1's genuine
  `FollowUpPromptTest` breakage sat alongside two flakes, and the only way to tell them apart was to
  re-run the suite three times and run the same suites against `master` for comparison.
- **The suite cannot gate anything.** A red build that is red anyway teaches everyone to ignore it, so
  CI cannot be trusted as a merge gate.
- Every merge decision now costs several minutes of forensic re-runs.

## Suggested Solutions

1. **Per-test topic isolation (preferred).** Give each test class (or each test) its own topic names —
   e.g. a `@TestProfile`/config source that suffixes `cs.integration`/`cs.commands`/`cs.results` with
   a per-class token — so no consumer can ever observe another test's records. Removes the coupling at
   the root and keeps the tests parallelizable.
2. **Unique consumer groups + explicit seek.** Keep shared topics, but give each test a fresh consumer
   group and record the end offset before acting, asserting only on records after that watermark.
   Smaller change; still leaves tests reading a shared log.
3. **Serialize the Kafka-backed tests.** Mark them `@ResourceLock`/single-threaded and reset topics
   between classes. Cheapest, but slowest, and it papers over the isolation defect rather than fixing it.

Option 1 with option 2's watermark assertion is the durable combination.
