---
name: kafkasends-seam-untested
description: KafkaSends.awaitAck maps three JDK exceptions onto BrokerAckFailure and no test drives it; every dispatch test mocks the emitter or the factory methods instead
metadata:
  type: project
---

`spire-orchestrator/.../pipeline/KafkaSends.java` `awaitAck` maps `TimeoutException` /
`InterruptedException` / `ExecutionException` onto `BrokerAckFailure.notAcknowledged` vs
`.rejected`. **No test in the repository exercises it.** `BrokerAckFailureTest` calls the two
factory methods directly; `RunResourceTest` mocks `RunCommandEmitter` and throws a
`BrokerAckFailure` ready-made. Nothing covers the seam between them.

Verified 2026-09-03 on commit `567a125`: changing the `TimeoutException` branch to
`BrokerAckFailure.rejected(...)` — which classifies a `java.util.concurrent.TimeoutException` as
`mayHaveLanded=false`, i.e. restores the exact pre-FR-F10 duplicate-run behaviour — leaves the
whole `:spire-orchestrator:test` task (949 tests) green.

**Why:** the tests were written at the two ends of the path, and the classification that decides
money lives in the middle. Same shape as the `SymbolIndexSeamTest` and circuit-breaker
(`failed future recorded as success`) lessons this project already paid for.

**How to apply:** when reviewing anything that touches `KafkaSends`, `BrokerAckFailure`, or the
run-dispatch path, do not read a green suite as covering the mapping — check for a
`KafkaSendsTest` first. A cheap direct test of `awaitAck` (a pre-failed / never-completing
`CompletableFuture`) closes it. See [[v51-do-block-silent-on-zero-match]].
