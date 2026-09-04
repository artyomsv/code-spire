---
name: orchestrator-docker-contention-signature
description: "spire-orchestrator full-suite red with 997 completed / 665 skipped + ConversationE2ETest ContainerLaunchException + 3 *ResourceTest initializationError NPEs is Docker contention, not a regression"
metadata:
  type: project
---

A full `:spire-orchestrator:test` that reports **`997 tests completed, 4 failed, 665 skipped`** in
~50s, with `ConversationE2ETest > replyInOwnedThreadYieldsAnswerFollowUp` failing on
`ContainerLaunchException: Timed out waiting for container port to open` and
`ContextProviderResourceTest` / `LlmProviderResourceTest` / `ProviderResourceTest` each failing as
`initializationError` with a bare NPE, is **Docker/Testcontainers contention**. It is not a code
regression. A healthy full run takes ~2m40s and reports 1057 tests, 0 skipped.

The 665 skipped is the tell: the suite aborted, it did not evaluate and fail.

**Why:** `spire-orchestrator` drives Dev Services (Postgres + Kafka) and `ConversationE2ETest`
launches a further container, but the module is **not** in the `DockerTestsAreSerialisedTest` lock
list (that covers only `spire-runtime-docker`, `spire-run-worker`, `spire-e2e`,
`spire-agent-image`). So a second Gradle build, or a machine already running ~30 containers, starves
the port wait and the whole suite collapses. Observed 2026-09-04: this signature reproduced three
times while a peer's build was live, then went green twice on a clear lane with the *same* files.

**How to apply:** never report these four as findings. When a full orchestrator run shows this
shape, check the lane (`Get-CimInstance Win32_Process -Filter "Name='java.exe'"` filtered for
`gradlew` / `GradleWorkerMain` — the two long-lived jdk-25 daemons are idle and do not count), then
re-run. Critically, **re-run the SUSPECT after the control**: a control-green/suspect-red pair proves
nothing when the reds all happened earlier under load. Only a suspect-red immediately following a
control-green on a verified-clear lane is evidence. See
[[gradle-concurrent-test-runs-corrupt-results]] and [[verify-tree-before-each-build]].
