# Code Review State: global / software-factory

Last reviewed: 2026-09-02
Rounds completed: 2

## Resolved (fixed in code; do not re-raise)
- [code-quality/C1] Docker volume name derived from the raw run id is illegal to the daemon (`::`, `/`) — SHA-256 digest name, id stays on the label; `DockerRunRuntimeTest` — round 1
- [code-quality/C2] STDIN harness received no prompt (kept off argv, delivered nowhere) — `SPIRE_PROMPT` for `PromptDelivery.STDIN`; `RunUnitBuilderTest` — round 1
- [code-quality/C3] Log frames split mid-line made a `pushed` report unparseable — partial lines carried across frames, flushed on complete — round 1; the carry is bounded at `MAX_LINE_CHARS` (64 KiB), clipped with a marker, remainder dropped to the next newline; `DockerRunRuntimeIT` — round 2
- [code-quality/H1] `RunDispatcher` described ack-on-receipt and implemented nothing — MANUAL ack after the claim, `@Blocking(ordered=true)`, `CompletionStage<Void>` return, `RunAckBudget` startup guard — round 1; the guard's number is now ENFORCED: `RunUnitBuilder` refuses a command whose wall clock exceeds `spire.run.max-wall-clock-seconds` as BAD_COMMAND; `RunUnitBuilderTest` — round 2
- [code-quality/H2] Re-dispatching a queued subject overwrote the row and answered 201 for a run the claim then dropped — `queued()` returns whether a row was written, 409 otherwise — round 1; the row is now written LAST, after the machine account (with a resolved login, else 409), the LLM key, the cap and the fully built command, so nothing between the row and the dispatch can throw and burn the subject; `RunResourceTest` — round 2
- [code-quality/H4] Result publish discarded its `CompletionStage`, so a broker refusal was silent — awaited with a 30s bound — round 1; a refused result is no longer rethrown after the ack (which only dead-lettered a command that had already run): logged, replaced once by a compact `RunFailed("RESULT_UNPUBLISHABLE")`, and the path lists are capped at 1000 in `PublisherOutcome`; `RunDispatcherTest`, `PublisherOutcomeTest` — round 2
- [code-quality/M1] Log-stream futures on the common pool — dedicated virtual-thread executor, stopped on `@PreDestroy` — round 1
- [code-quality/M2] `PublisherOutcome` swallowed a parse failure silently and a failure after a push still reported the push — debug log; `failedAfterPush` outranks `pushedRef` — round 1; a NON-terminal failure (`BUNDLE_UNREADABLE`) after a push no longer discards the push; `PublisherOutcomeTest`, `RunLauncherTest` — round 2
- [code-quality/M3] Catch blocks too wide in `PublishCycle`, `PublisherMain`, `OutcomeWriter`, `RunClaimStore` — narrowed to the checked types each block can see — round 1
- [code-quality/M8] `baseCommit` accepted an abbreviation that JGit then refused after the agent was paid — 40-hex only; nested GitLab namespaces accepted per segment — round 1
- [code-quality/L1] `Optional.get()` → `orElseThrow()` at three sites — round 1
- [code-quality/L2] `RunCommand.baseCommit` had no null check — round 1
- [code-quality/L4] `PublishRepo.safe()` accepted a newline in a path; `Files.walk` stream not closed — round 1
- [code-quality/L5] `spire-publisher` ran in the service tier despite needing no Docker — moved to `fastTestModules` — round 1
- [rules/R1] `SPIRE_RUN_WORKER_HTTP_PORT` missing from `.env.example` — added — round 1
- [security/H-1] Machine-account write token and LLM key rode Kafka in cleartext and landed in `dlq_entry.payload` — Tink-packed per run (`RunCredentials`, AAD from `RunCommand.scmCredentialAad`/`harnessCredentialAad`), the worker's `Credentials` unpacks; the login rides inside the envelope (`MachineAccountCredential`) so the worker names no account; `CredentialsTest`, `RunUnitBuilderTest` — round 1, completed round 2
- [security/H-2] FACTORY role unreachable through REST (`resolveIdentity` rebuilt the input without it); machine account silently became the review bot — role carried through on create and update, on the view, 400 on an unknown role; `ProviderResourceTest` — round 1; a PUT that carries NO role (the dashboard's edit form) now keeps the stored role (`COALESCE`), so editing a FACTORY provider in Settings no longer demotes it; `ProviderResourceTest.aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate` — round 2
- [security/M-1 (cap half)] `POST /api/runs` applied none of ADR-025's gates while V40 counts run spend in the window — `SpendGate.decide()` before the queued row, 429; `RunResourceTest` — round 1
- [security/M-2] `spire-run-worker` had no operator-authentication posture — OIDC block, deny-by-default `/rw/*`, health public, `OperatorAuthorization` refusals; `OperatorAuthTest` — round 1; a catch-all `deny` on `/*` (Quarkus permits what matches no entry) and the `/rw/auth/login|logout|callback` endpoints every deployable exposes; `OperatorAuthTest.aPathOutsideEveryPrefixIsDeniedNotPermitted` — round 2
- [security/M-3] Push gate missed a Jenkinsfile below the root — `**/Jenkinsfile`, `**/Jenkinsfile.*`; `PushGateTest` — round 1
- [security/M-4] `.yml`/`.yaml` coverage asymmetric — `.woodpecker.yaml`, `cloudbuild.yml`, `.drone.yaml`, `azure-pipelines.yaml`, `buildspec.yaml`; `PushGateTest` — round 1
- [security/M-5] Unbounded, quadratic run-event accumulation on a shared worker — `RunEventFold` keeps bounded usage events + one flag; `RunEventFoldTest` — round 1; the pre-parse line buffer is bounded too (see C3) — round 2
- [security/L-1] Credential scrub was literal-only — percent-encoded and Basic-auth forms scrubbed too; `OutcomeWriterTest` — round 1; `CloneMain` uses the username-aware writer — round 2
- [security/(cr-B)] `dispatchFailed` reused the (queued, running) guard and could overwrite a run that had started — guarded on `queued` only — round 1; the three result guards now ALSO accept a `failed / DISPATCH_FAILED` row (the real result is the acknowledgement the broker never sent) and clear the superseded failure; `FactoryRunProjectionTest.aRealResultCorrectsARowWhoseDispatchWasNeverAcknowledged` — round 2
- [security/(sec-A)] `DlqTopics` had no route for run commands and results — routed to `cs.run-commands` / `cs.run-results` — round 1
- [qa/M-1] `RunDispatcher` and `RunLauncher` had no test; `RunClaimStore` fail-closed path untested — `RunDispatcherTest`, `RunLauncherTest`, `RunClaimStoreFailClosedTest` — round 1; `RunResultSagaTest` added; `RunLauncherTest` covers a failed salvage after a push, a throwing salvage, and an unreadable bundle after a push — round 2
- [qa/L-1] `HarnessRegistry.forName` refusals — covered in `RunUnitBuilderTest.anUnknownHarnessFailsBeforeAnythingIsCreated` — round 1
- [qa/L-2] `Credentials.scm` refusal and masked `toString` — `CredentialsTest` (envelope round trip, wrong-run AAD, raw token, masked toString, neutral harness key) — round 2
- [security/(sec-A 9)] `create()` waited on the init container with no timeout — bounded (15 min), stopped on expiry — round 1
- [security/(sec-B 7, cr-B 6)] re-arming a `DISPATCH_FAILED` row kept the first request's parameters while dispatching the second's — the re-arm takes the new ones; `FactoryRunProjectionTest` — round 1
- [security/(sec-B 12/13, cr-B 9)] prompt/model/baseBranch unbounded; broker exception text stored viewer-readable — bounded and validated; a fixed detail is stored, the exception logged; `RunResourceTest` — round 1
- [rules/(rules-A 8/9/16)] run worker lacked the `service`/`env` JSON log fields, the run id was concatenated into messages instead of on the MDC, `%test` bound a fixed port — round 1; the MDC now opens at the top of `onCommand`, so the cancel and redelivery lines carry the run id too — round 2
- [live/M0] `drainPublisher` issued `docker stop` the instant the agent exited (SIGTERM at 0s, exit 143, nothing pushed, nothing reported) — waits the drain window first, stops only if it elapses — round 1; the wall-clock path no longer SIGKILLs the publisher through `cancel()` one line before that window (`killAgent` only), and the window is 300s, sized to a final fetch-gate-push rather than 30s; `DockerRunRuntimeIT.anAgentOverrunStillGivesThePublisherItsDrainWindow` — round 2
- [cr-R2 2 / sec-R2 11] A FACTORY provider with no resolved login was a 500 after the queued row (a burned subject); two more throw sites sat between the row and the dispatch — 409 naming the missing login, and the row is written last; `RunResourceTest.aFactoryAccountWithNoResolvedLoginIsRefusedBeforeAnyRowExists` — round 2
- [cr-R2 5] `RunLauncher.launch` had no guard around salvage and the readers: a throwing salvage skipped `interpret` and `destroy`, discarding a completed run's result — readers cancelled, `SALVAGE_FAILED` reported with what the publisher had pushed, unit preserved by label; `RunLauncherTest` — round 2
- [cr-R2 4 (partial)] A wall-clock overrun's `SALVAGE_FAILED` now names the ref the publisher had pushed in its detail; the distinct terminal shape stays in `techdebt/spire-run-worker/2-3-…` — round 2
- [cr-R2 10, 22] Run attention row's action is a UI route, and its query carries a LIMIT — round 2
- [cr-R2 12] `RunResourceTest` ran an unqualified `DELETE FROM llm_provider` in the shared database — scoped to its own `TEST-run-` fixtures — round 2
- [cr-R2 14] `CallRefs.forRun` took an attempt the run id already carries — dropped; blank run id and sequence refused; `LlmChargeSubjectTest` — round 2
- [cr-R2 15, 19, 23] `RunIds.parse` accepted a non-canonical attempt (`01`, `+1`); `HandoffWatcher` materialised the whole listing before its cap; `PushGate` recompiled the profile per bundle and skipped a blank path — canonical check; `.limit()` before `.toList()`; `decideCompiled` with the profile compiled once in `PublishCycle`, a blank path refused; tests beside each — round 2
- [cr-R2 test items 1–5] `M0WalkingSkeletonTest`'s redundant claim test removed; the role update test sends NO role; `RunLauncherTest` covers the push-then-failed-salvage half; `AttentionQueriesTest` inserts FACTORY rows so both role filters are protected; `MachineAccountsTest`'s `assertThrows` wrapper removed — round 2
- [sec-R2 5 / (cr-B 8, sec-B 5)] `AttentionQueries` counted FACTORY rows as reviewers — `role = 'REVIEWER'` on both queries; `AttentionQueriesTest` — round 2
- [rules-R2 1, 2, 6, 11, 12, 13, 14, 15, 16] `RunResource.dispatch` 96 lines / file 339 lines — `DispatchRequestParser` extracted; `FactoryRunProjection.queued` 7 parameters — `QueuedRun` record; publisher image hardcoded to `latest` — `spire.run.publisher-image`, no default in prod, `SPIRE_PUBLISHER_IMAGE`; agent image and wall clock without env override — `SPIRE_FACTORY_AGENT_IMAGE_CODEX`, `SPIRE_FACTORY_WALL_CLOCK_SECONDS`, `SPIRE_RUN_MAX_WALL_CLOCK_SECONDS`, all in `.env.example`; run id outside the MDC span; CLAUDE.md's stale tier line, wrong module count and empty fence — round 2
- [rules-R2 17] Three round-1 Open items had no debt file — `techdebt/spire-publisher/3-2-a-non-fast-forward-push-has-no-handling.md`, `techdebt/spire-runtime-docker/4-3-the-agent-container-on-the-default-bridge-…`, and `CredentialsTest` in place of the third — round 2
- [rules-A 4, 5 / rules-B 5, 6, 8] `OPENAI_API_KEY` named in core — `HarnessInvocation.CREDENTIAL`, translated by the Codex arm; `spire-bot` hardcoded — the login rides in the envelope; `RunResultSaga` `@Blocking` + MDC; an unknown provider role is a 400 — round 2

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- (none)

## Open (each tracked as a techdebt/ entry; not fixed in these rounds)
- [code-quality/H3, cr-A 7, sec-R2 6] No lease, no orphan reclaim on worker restart; a preserved unit keeps the write token in container config — `techdebt/spire-run-worker/2-4-…`
- [cr-A 4 / cr-R2 4] A failed salvage's terminal shape hides delivered pushes beyond the detail line — `techdebt/spire-run-worker/2-3-…`
- [cr-A 8 / cr-R2 9] A gate refusal stops the publisher, not the agent — `techdebt/spire-run-worker/3-3-a-gate-refusal-…`
- [cr-A 9 / cr-R2 20] `CancelRun` is a no-op — `techdebt/spire-run-worker/3-3-cancelrun-…`
- [cr-A 11, 13; sec-A 10] every publisher failure retryable; `RunStarted.providerRunId` is the run id; the worker's own failure details unscrubbed — `techdebt/spire-run-worker/4-*`
- [code-quality/M5] Non-fast-forward push has no handling — `techdebt/spire-publisher/3-2-a-non-fast-forward-…`
- [sec-A 8] Cumulative bundle bytes and object growth — `techdebt/spire-publisher/3-2-cumulative-…`
- [code-quality/M6 / rules-B 12 / sec-R2 (ledger)] `tokenUsage` dropped; no `llm_charge` writer for runs — `techdebt/spire-orchestrator/3-3-run-token-usage-…`
- [cr-B 3 / cr-R2 7] A run that delivered nothing is `succeeded` — `techdebt/spire-orchestrator/3-2-a-run-that-delivered-nothing-…`
- [rules-B 16 / sec-B 13] `failureDetail` viewer-readable — `techdebt/spire-orchestrator/4-1-a-runs-failure-detail-…`
- [sec-R2 2] A run defaults to the reviewer's own model key — `techdebt/spire-orchestrator/3-3-a-run-defaults-to-the-reviewers-own-model-key.md`
- [sec-R2 8, 9] Commit authorship agent-controlled; no content floor at the gate — `techdebt/spire-publisher/4-3-commit-authorship-…`
- [sec-R2 12] Test origin runs as root on the shared bridge; bases pinned by tag — `techdebt/spire-run-worker/4-2-the-m0-test-origin-…`
- [security/L-2] Agent container on the default bridge — `techdebt/spire-runtime-docker/4-3-…`
- [rules-R2 3–5, 7–10] Method size and parameter count in the dispatch path — `techdebt/global/4-2-the-factory-dispatch-path-…`
- [cr-R2 13] `testServices` requires the `docker` CLI and builds three images per run — recorded in CLAUDE.md's Build & run; the images move to GHCR with M1's packaging
