# Code Review State: global / software-factory

Last reviewed: 2026-09-02
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [code-quality/C1] Docker volume name derived from the raw run id is illegal to the daemon (`::`, `/`) — SHA-256 digest name, id stays on the label; `DockerRunRuntimeTest` — round 1
- [code-quality/C2] STDIN harness received no prompt (kept off argv, delivered nowhere) — `SPIRE_PROMPT` for `PromptDelivery.STDIN`; `RunUnitBuilderTest` — round 1
- [code-quality/C3] Log frames split mid-line made a `pushed` report unparseable — partial lines carried across frames, flushed on complete — round 1
- [code-quality/H1] `RunDispatcher` described ack-on-receipt and implemented nothing — MANUAL ack after the claim, `@Blocking(ordered=true)`, `CompletionStage<Void>` return, `RunAckBudget` startup guard, `max.poll.records=1` / `max-queue-size-factor=1` / 65-minute threshold; `RunDispatcherTest`, `RunAckBudgetTest` — round 1
- [code-quality/H2] Re-dispatching a queued subject overwrote the row and answered 201 for a run the claim then dropped — `queued()` returns whether a row was written, 409 otherwise; `RunResourceTest` — round 1
- [code-quality/H4] Result publish discarded its `CompletionStage`, so a broker refusal was silent — awaited with a 30s bound, failure logged with the result — round 1
- [code-quality/M1] Log-stream futures on the common pool — dedicated virtual-thread executor, stopped on `@PreDestroy` — round 1
- [code-quality/M2] `PublisherOutcome` swallowed a parse failure silently and a failure after a push still reported the push — debug log; `failedAfterPush` outranks `pushedRef`; `PublisherOutcomeTest` — round 1
- [code-quality/M3] Catch blocks too wide in `PublishCycle`, `PublisherMain`, `OutcomeWriter`, `RunClaimStore` — narrowed to the checked types each block can see — round 1
- [code-quality/M8] `baseCommit` accepted an abbreviation that JGit then refused after the agent was paid — 40-hex only; nested GitLab namespaces accepted per segment — round 1
- [code-quality/L1] `Optional.get()` → `orElseThrow()` at three sites — round 1
- [code-quality/L2] `RunCommand.baseCommit` had no null check — round 1
- [code-quality/L4] `PublishRepo.safe()` accepted a newline in a path; `Files.walk` stream not closed — round 1
- [code-quality/L5] `spire-publisher` ran in the service tier despite needing no Docker — moved to `fastTestModules` — round 1
- [rules/R1] `SPIRE_RUN_WORKER_HTTP_PORT` missing from `.env.example` — added — round 1
- [security/H-1] Machine-account write token and LLM key rode Kafka in cleartext and landed in `dlq_entry.payload` — Tink-packed per run (`RunCredentials`, AAD from `RunCommand.scmCredentialAad`/`harnessCredentialAad`), worker `Credentials` decrypts; `RunUnitBuilderTest` asserts a ciphertext for another run does not open — round 1
- [security/H-2] FACTORY role unreachable through REST (`resolveIdentity` rebuilt the input without it); machine account silently became the review bot — role carried through, written on update, exposed on `ProviderView`; REST-level test — round 1
- [security/M-1 (cap half)] `POST /api/runs` applied none of ADR-025's gates while V40 counts run spend in the window — `SpendGate.decide()` before the queued row, 429; `RunResourceTest` — round 1
- [security/M-2] `spire-run-worker` had no operator-authentication posture — OIDC block, deny-by-default `/rw/*`, health public, `OperatorAuthorization` refusals; `OperatorAuthTest` — round 1
- [security/M-3] Push gate missed a Jenkinsfile below the root — `**/Jenkinsfile`, `**/Jenkinsfile.*`; `PushGateTest` — round 1
- [security/M-4] `.yml`/`.yaml` coverage asymmetric — `.woodpecker.yaml`, `cloudbuild.yml`, `.drone.yaml`, `azure-pipelines.yaml`, `buildspec.yaml`; `PushGateTest` — round 1
- [security/M-5] Unbounded, quadratic run-event accumulation on a shared worker — `RunEventFold` keeps bounded usage events + one flag; `RunEventFoldTest` — round 1
- [security/L-1] Credential scrub was literal-only — percent-encoded and Basic-auth forms scrubbed too; `OutcomeWriterTest` — round 1
- [security/(cr-B)] `dispatchFailed` reused the (queued, running) guard and could overwrite a run that had started — guarded on `queued` only; `FactoryRunProjectionTest` — round 1
- [security/(sec-A)] `DlqTopics` had no route for run commands and results — routed to `cs.run-commands` / `cs.run-results` — round 1
- [qa/M-1] `RunDispatcher` and `RunLauncher` had no test; `RunClaimStore` fail-closed path untested — `RunDispatcherTest`, `RunLauncherTest`, `RunClaimStoreFailClosedTest` — round 1
- [qa/L-1] `HarnessRegistry.forName` refusals — covered in `RunUnitBuilderTest.anUnknownHarnessFailsBeforeAnythingIsCreated` — round 1
- [security/(sec-A 9)] `create()` waited on the init container with no timeout — bounded (15 min), stopped on expiry — round 1
- [security/(sec-B 7, cr-B 6)] re-arming a `DISPATCH_FAILED` row kept the first request's parameters while dispatching the second's — the re-arm takes the new ones; `FactoryRunProjectionTest` — round 1
- [security/(sec-B 12/13, cr-B 9)] prompt/model/baseBranch unbounded; broker exception text stored viewer-readable — bounded and validated; a fixed detail is stored, the exception logged; `RunResourceTest` — round 1
- [rules/(rules-A 8/9/16)] run worker lacked the `service`/`env` JSON log fields, the run id was concatenated into messages instead of on the MDC, `%test` bound a fixed port — all three aligned with the other deployables — round 1
- [live/M0] `drainPublisher` issued `docker stop` the instant the agent exited (SIGTERM at 0s, exit 143, nothing pushed, nothing reported) — waits the drain window first, stops only if it elapses; `DockerRunRuntimeIT.thePublisherGetsItsDrainWindow…` (mutation-verified) — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- (none)

## Open (tracked in the PR body, not fixed this round)
- [code-quality/H3] No orphan-run reclaim on worker restart (`discoverOrphans` exists, nothing calls it)
- [code-quality/M5] Publisher push is fast-forward-only with no non-fast-forward handling
- [code-quality/M6] `FactoryRunProjection.finished()` drops `RunFinished.tokenUsage`; no `llm_charge` writer for runs (`CallRefs.forRun` has no production caller) — the ledger half of security/M-1
- [code-quality/M7] `spire-publisher:latest` image build — owned by the M0 walking-skeleton work
- [security/L-2] Agent container on the default bridge can reach host-published ports in dev
- [qa/L-2] `Credentials.scm` refusal and masked `toString` — now covered indirectly by `RunUnitBuilderTest`; no dedicated test
