# Harness reviews, PR 3: the agent holds a placeholder, a proxy adds the credential — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a proxied run unit the agent container holds no usable model credential. It talks plain HTTP to a
per-run **model proxy** (`spire-model`), which holds the real API key or seat token, adds it to each model request and
forwards it over HTTPS through the step 2 egress proxy. A key the vendor refuses still fails the run as
`CREDENTIAL_REJECTED`; a model path the harness did not declare fails it as `BLOCKED_EGRESS`.

**Architecture:** One more container per proxied unit, `spire-model-proxy` (a small Java 25 service, our own image, built
like the publisher). It joins **only** the unit's internal network, under the alias `spire-model`, and reaches the
vendor through `spire-egress` (so the egress record and allowlist stay the single outbound gate). The agent loses its
proxy variables: its only reachable peer is `spire-model`. What to inject, where to forward and which paths to allow
comes from the harness adapter (`HarnessAdapter.credentialRoute`), never from the repository or the ticket. The agent
side is configured by the adapter too: Codex gets a custom model provider pointing at `spire-model`, plus a
placeholder `auth.json` for a seat.

**Tech Stack:** Java 25 (`com.sun.net.httpserver`, `java.net.http.HttpClient`), docker-java (`spire-runtime-docker`),
Quarkus run-worker, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` — "Final design review" (the agent must never
hold a usable credential; a credential-injecting proxy adds it; placeholder in the agent), delivery step 3, and "After
step 2" (step 3 is built right before step 8; the step 3 measurements). Live spike of 2026-11-07/08 (below).

## Global Constraints

- The agent container never receives a usable model credential in a proxied unit: not in its environment, argv,
  mounts or files. Only placeholders (`TEST-PLACEHOLDER`-style constants defined in code).
- The route (upstream origin, injection kind, allowed path prefixes) comes from the harness adapter, never from the
  reviewed repository, the ticket or the prompt.
- The model proxy logs method, path without query, status and duration only. Never a header value, a body or a query.
- No `seccomp=unconfined`, no added capabilities; `CapDrop ALL`, no-new-privileges, read-only root filesystem and a
  memory limit on the model proxy container, as on the egress proxy.
- `auth.openai.com` is never declared and never reachable from the unit (refresh tokens stay in the orchestrator).
- **Claude Code with a Claude subscription is never built as a usable path and never run, in tests or by hand.**
  Anthropic forbids third-party products to route requests through Free/Pro/Max/Ultra credentials or to intermediate
  claude.ai session tokens (https://code.claude.com/docs/en/legal-and-compliance, read 2026-11-08). This PR adds no
  Anthropic OAuth injection kind. Claude is API key only (PR 3b).
- No local build/test/lint (CI only). Docker-driving tests stay under the `dockerDaemonLock`
  (`DockerTestsAreSerialisedTest`).
- Commits: imperative, at most 72 characters, no AI attribution. One PR, `review: requested` after each push of fixes.

## Measured in the spike (2026-11-07/08, real credentials, closed network, report-only)

| Harness | Payment | Agent config | Paths seen (all via the proxy, placeholder only) |
|---|---|---|---|
| Codex 0.156.1 / 0.166.0 | API key | `model_provider=spire`, `model_providers.spire.base_url=http://<proxy>/v1`, `env_key=<placeholder var>`, `wire_api=responses` | `POST /v1/responses` (SSE) |
| Codex 0.156.1 / 0.166.0 | Seat | same provider with `base_url=http://<proxy>/backend-api/codex`, `requires_openai_auth=true`, `chatgpt_base_url=http://<proxy>/backend-api/`; placeholder `auth.json` (unsigned JWTs, `exp` 2100, empty refresh token) | `GET /backend-api/codex/models`, `GET /backend-api/wham/accounts/check`, `GET /backend-api/wham/settings/user`, `POST /backend-api/codex/responses` (SSE). Compaction uses the same path. No redirect. |
| Claude Code 2.1.319 | API key | `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY=<placeholder>`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` | `POST /v1/messages` (SSE) — PR 3b |
| opencode 1.19.5 | API keys | provider `baseURL` + `apiKey` placeholder in `opencode.json`, `OPENCODE_DISABLE_MODELS_FETCH=1` | `POST /v1/messages`, `POST /v1/responses` — PR 3c |

Codex extra calls stay off (`features.plugins=false`, `analytics.enabled=false`, seat: `features.apps=false`). The seat
injection needs `Authorization: Bearer <access_token>` and `chatgpt-account-id: <account_id>`; plain HTTP inside the unit
works, no TLS termination. Not seen in any run: the `*.oaiusercontent.com` hosts from step 2 (probably file or image
downloads) and WebSocket upgrades.

## Decisions (designer's calls)

- **Scope.** This PR: the model proxy, the runtime wiring, and Codex on both payments. Claude Code (API key, with the
  subscription mode as a disabled branch) is **PR 3b** and opencode (API keys) is **PR 3c**, each its own plan. Fork
  reviews stay refused until step 8; this PR changes no fork gate.
- **Where injection happens.** A separate container, not tinyproxy: tinyproxy only CONNECT-tunnels. The model proxy
  sits on the internal network only and forwards through `spire-egress`, so the model proxy has no route of its own and
  the egress allowlist (the adapter's `egressHosts`) still decides what leaves.
- **Which runs.** `spire.run.credential-proxy.mode` = `OFF` | `REVIEWS` | `ALL`, default `REVIEWS` (run kinds REVIEW,
  RECONCILE, FOLLOWUP). `ALL` adds factory builds later, once measured there. It needs an egress network: with
  `spire.run.egress.mode=off` the unit is built as today and the worker logs one WARN at startup naming both settings.
- **Injection kinds** (`CredentialRoute.Injection`): `BEARER` (replace `Authorization`), `X_API_KEY` (remove
  `Authorization`, set `x-api-key`; used by PR 3b/3c, unit-tested here), `CHATGPT_SEAT` (from the signed-in file:
  `Authorization: Bearer <tokens.access_token>`, `chatgpt-account-id: <tokens.account_id>`). Every incoming
  `authorization`, `x-api-key` and `chatgpt-account-id` header is dropped first, so whatever the agent sends is
  ignored. There is deliberately no Anthropic OAuth kind.
- **Allowed paths** per Codex route (prefix match, path only): API key `/v1/responses`, `/v1/models`; seat
  `/backend-api/codex/`, `/backend-api/wham/`. Any other path, any absolute-form or CONNECT request, and any WebSocket
  upgrade answer `403`/`501` without contacting the vendor, and are logged as `refused`.
- **Outcome reading.** The model proxy prints one line per request to stdout:
  `spire-model <METHOD> <path> -> <status|refused>`. The runtime reads that log when the agent ends, like the egress
  log, into `ModelProxyReport(int requests, int rejected, List<String> refusedPaths)`. In `RunLauncher.agentFailure`, a
  report with `rejected > 0` (upstream 401/403) becomes `CREDENTIAL_REJECTED` (the pool marks the key, as exit 120 does
  today); refused paths become `BLOCKED_EGRESS` with the detail `model path <path> refused`.
- **Secrets.** The model proxy receives the credential as environment (`SPIRE_MODEL_SECRET`), like every container
  today; the worker's `LiveSecrets` scrub already covers its value. Its stdout is never forwarded raw: only the
  parsed report leaves the runtime.
- **Codex in a proxied unit.** No `codex login` step: the API key path uses `env_key` with a placeholder variable,
  and the seat path writes a placeholder `auth.json` built by the adapter. `egressHosts` is unchanged
  (`api.openai.com` or `chatgpt.com`); it now governs the model proxy's traffic, since the agent has no proxy at all.
- **Corporate network.** The model proxy gets the enterprise trust environment (CA bundle) like the agent did, and
  `HTTPS_PROXY=http://spire-egress:8888`; the egress proxy chains to the corporate proxy as today.
- **Image.** `spire-model-proxy:latest`, config `spire.run.credential-proxy.image`
  (`SPIRE_RUN_CREDENTIAL_PROXY_IMAGE`). Built locally like the publisher (`docs` updated: four factory images). Not
  on GHCR in this PR.

## Review Focus

1. A Codex upgrade starts calling a path the route does not allow: the run must fail visibly as `BLOCKED_EGRESS`
   naming the path, never hang or silently succeed without a review. (Task 4 test.)
2. The vendor refuses the injected key or seat (401/403): the run must end `CREDENTIAL_REJECTED` so the pool marks
   the credential, even though Codex never logs in and never exits 120. (Task 4 test.)
3. A streamed model answer (SSE) that takes minutes: the proxy must pass each chunk on as it arrives, not buffer the
   whole response. (Task 1 test.)
4. The real credential must not appear in the agent's environment, argv or files, nor in any proxy log line, for both
   payments. (Task 1 and Task 3 tests.)
5. `credential-proxy.mode=REVIEWS` with `egress.mode=off`: the run must still run as today (no unreachable
   `spire-model`), with the WARN. (Task 4 test.)

---

### Task 1: The model proxy service and image

**Files:**
- Create: `spire-model-proxy/build.gradle.kts`, `spire-model-proxy/Dockerfile`, `spire-model-proxy/.dockerignore`
- Create: `spire-model-proxy/src/main/java/dev/codespire/modelproxy/ModelProxy.java` (main, server, forwarding)
- Create: `spire-model-proxy/src/main/java/dev/codespire/modelproxy/ProxyRoute.java` (route from environment)
- Create: `spire-model-proxy/src/main/java/dev/codespire/modelproxy/Injector.java` (header rewrite per kind)
- Modify: `settings.gradle.kts` (`include("spire-model-proxy")`), root `build.gradle.kts` (add to the pure-JDK/fast module lists the way `spire-secrets` is listed at line ~221)
- Test: `spire-model-proxy/src/test/java/dev/codespire/modelproxy/ModelProxyTest.java`, `InjectorTest.java`, `ProxyRouteTest.java`

**Interfaces:**
- Produces (environment contract of the image, read by Task 2/4):
  - `SPIRE_MODEL_UPSTREAM` — origin, e.g. `https://chatgpt.com` (tests: `http://127.0.0.1:<port>`)
  - `SPIRE_MODEL_INJECTION` — `BEARER` | `X_API_KEY` | `CHATGPT_SEAT`
  - `SPIRE_MODEL_PATHS` — comma-separated allowed path prefixes
  - `SPIRE_MODEL_SECRET` — the key, or the whole signed-in `auth.json` for `CHATGPT_SEAT`
  - `HTTPS_PROXY` — optional, `http://spire-egress:8888`
  - listens on port `8080`; stdout lines `spire-model <METHOD> <path> -> <status>` or `-> refused`
- Produces: `record ProxyRoute(URI upstream, Injection injection, List<String> pathPrefixes)` with
  `static ProxyRoute fromEnvironment(Map<String, String> env)`; `enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }`;
  `final class Injector` with `static Map<String, List<String>> outgoing(Map<String, List<String>> incoming, Injection kind, String secret)`.

- [ ] **Step 1: Write the failing tests.** `ModelProxyTest` starts the proxy in-process against a JDK `HttpServer`
  upstream on `127.0.0.1` and asserts:
  - `replacesWhateverCredentialTheAgentSent`: agent sends `Authorization: Bearer TEST-PLACEHOLDER`; upstream sees
    `Bearer TEST-secret-key` and no other `authorization`.
  - `aSeatSendsTheAccessTokenAndTheAccountId`: secret is an `auth.json` with `tokens.access_token=TEST-access`,
    `tokens.account_id=TEST-account`; upstream sees `Bearer TEST-access` and `chatgpt-account-id: TEST-account`, even
    when the agent sent its own `chatgpt-account-id`.
  - `anXApiKeyRouteSendsNoAuthorization`: upstream sees `x-api-key: TEST-secret-key` and no `authorization`.
  - `anUndeclaredPathIsRefusedWithoutReachingTheVendor`: `GET /v1/files` → 403, upstream hit count 0, stdout line
    `spire-model GET /v1/files -> refused`.
  - `aWebSocketUpgradeIsRefused`: `Upgrade: websocket` → 501, upstream not contacted.
  - `aStreamIsPassedOnAsItArrives`: upstream writes `data: TEST-1\n\n`, then blocks on a latch; the client reads
    `data: TEST-1` before the latch is released.
  - `theLogNamesNoSecretAndNoQuery`: `POST /v1/responses?key=TEST-q` with the secret → stdout contains
    `spire-model POST /v1/responses -> 200` and neither `TEST-secret-key`, `TEST-access` nor `TEST-q`.
  - `aRefusedKeyIsLoggedWithItsStatus`: upstream 401 → client gets 401, stdout `-> 401`.
  `ProxyRouteTest`: a missing or blank variable, an unknown injection, a non-`https` upstream other than `127.0.0.1`
  test hosts, and an empty path list each throw `IllegalArgumentException` naming the variable.
- [ ] **Step 2: Push; CI `fast tests` fails** on the missing classes (compile) — record the run id.
- [ ] **Step 3: Implement.** `ModelProxy.main` reads `ProxyRoute.fromEnvironment(System.getenv())` and the secret,
  serves `HttpServer` on `0.0.0.0:8080` with a virtual-thread executor; forwards with one `HttpClient`
  (`ProxySelector` from `HTTPS_PROXY` when set, redirects `NEVER`, connect timeout 10 s), body as
  `BodyPublishers.ofInputStream`, response as `BodyHandlers.ofInputStream` copied to the exchange in 8 KiB writes
  with a flush after each (chunked response: `sendResponseHeaders(status, 0)`). Hop-by-hop headers and `host`,
  `content-length` are not copied. Exit non-zero at start on an invalid route, naming the variable only.
  `Dockerfile`: `eclipse-temurin:25-jre-alpine`, the `APK_UPGRADE_BUST` upgrade layer, uid 1002, `EXPOSE 8080`,
  `ENTRYPOINT java -XX:MaxRAMPercentage=75 -cp /opt/spire-model-proxy/lib/* dev.codespire.modelproxy.ModelProxy`.
- [ ] **Step 4: Push; CI `fast tests` passes.**
- [ ] **Step 5: Commit** (`Add the model proxy that injects a run's credential`).

### Task 2: The runtime starts the model proxy beside the agent

**Files:**
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/RunUnitSpec.java` (component `ContainerSpec modelProxy`, nullable, after `egress`; wither `withModelProxy`; the 10/11-arg constructors pass `null`)
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/RunUnitSpec.java:118-136` (`environmentFor`: when `modelProxy != null`, the agent gets the trust environment only — no proxy variables)
- Create: `spire-runtime/src/main/java/dev/codespire/runtime/ModelProxyReport.java`
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/RunRuntime.java` (`default ModelProxyReport modelProxyOf(RunHandle handle) { return ModelProxyReport.NONE; }`)
- Create: `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/ModelProxyContainer.java`, `ModelProxyLogParser.java`
- Modify: `spire-runtime-docker/.../DockerRunRuntime.java` (create after the egress proxy, stop in `stopProxy`, remove on destroy and in the orphan sweep; `modelProxyOf`)
- Modify: `spire-runtime-docker/.../DockerRunNetworks.java` (connect the model proxy to the internal network only, alias `spire-model`)
- Test: `spire-runtime/src/test/.../RunUnitSpecTest.java`, `spire-runtime-docker/src/test/.../ModelProxyLogParserTest.java`, `DockerRunRuntimeIT.java`

**Interfaces:**
- Consumes: Task 1's image and environment contract.
- Produces: `RunUnitSpec.withModelProxy(ContainerSpec)`; `RunUnitSpec.MODEL_PROXY_ALIAS = "spire-model"`,
  `MODEL_PROXY_ADDRESS = "http://spire-model:8080"`; `record ModelProxyReport(int requests, int rejected, List<String> refusedPaths)` with `static final ModelProxyReport NONE`; `RunRuntime.modelProxyOf(RunHandle)`.

- [ ] **Step 1: Write the failing tests.**
  - `RunUnitSpecTest.anAgentBehindTheModelProxyHasNoProxyVariables`: with egress `REPORT` and a model proxy, the
    agent's environment has no `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` (any case); without a model proxy it keeps them.
  - `RunUnitSpecTest.aModelProxyNeedsAnEgressNetwork`: egress `OFF` plus a model proxy throws `IllegalArgumentException`.
  - `ModelProxyLogParserTest`: lines `-> 200`, `-> 401`, `-> 403`, `-> refused` give `requests=4, rejected=2,
    refusedPaths=[that path]`; other lines are ignored; the 8 MiB / 256-entry caps of `ProxyLogParser` apply.
  - `DockerRunRuntimeIT` (builds `spire-model-proxy` in `@BeforeAll` like the egress image; a stub upstream container
    on the outbound side):
    - `theModelProxyIsOnTheInternalNetworkOnly`: the model proxy container has exactly one network, the unit's internal one.
    - `theAgentReachesOnlyTheModelProxy`: from the agent, `http://spire-model:8080/v1/responses` answers;
      `http://spire-egress:8888` and any outside host are unreachable.
    - `theModelProxysTrafficIsRecordedByTheEgressProxy`: in `REPORT`, the stub upstream host appears in `egressOf`.
    - `destroyRemovesTheModelProxy`: no container with the run label remains.
    - `theReportCountsARejectedCall`: stub answers 401 → `modelProxyOf(handle).rejected() == 1`.
- [ ] **Step 2: Push; CI shows the failures** (compile for the new types, assertions for the IT) — record run ids.
- [ ] **Step 3: Implement.** `ModelProxyContainer` mirrors `EgressProxyContainer` (192 MiB memory — a JVM, not
  tinyproxy's 64 MiB — 0.5 CPU,
  64 pids, read-only root, `/tmp` tmpfs `size=8m`, `CapDrop ALL`, no-new-privileges), started after
  `networks.open(...)`, waited for by probing `/proc/net/tcp` for port 8080. Its environment is the spec's
  `modelProxy.environment()` plus the enterprise trust environment and `HTTPS_PROXY=EgressPolicy.PROXY_ADDRESS`.
- [ ] **Step 4: Push; CI `service tests + packaging` passes.**
- [ ] **Step 5: Commit** (`Start a model proxy beside the agent on the internal network`).

### Task 3: The harness declares its credential route; Codex uses it

**Files:**
- Create: `spire-harness/src/main/java/dev/codespire/harness/CredentialRoute.java`
- Modify: `spire-harness/.../HarnessAdapter.java` (`default Optional<CredentialRoute> credentialRoute(HarnessInvocation invocation) { return Optional.empty(); }`)
- Modify: `spire-harness/.../HarnessInvocation.java` (component `boolean credentialProxied`, last; the existing convenience constructors pass `false`; wither `withCredentialProxied(boolean)` enumerating every component)
- Modify: `spire-harness-codex/.../CodexAdapter.java` (`command`, `environment`, `credentialRoute`; new `PlaceholderSignIn` helper class in the same package)
- Modify: `spire-harness/src/testFixtures/.../HarnessAdapterContract.java` (proxied cases)
- Test: `spire-harness-codex/src/test/.../CodexAdapterTest.java`, `PlaceholderSignInTest.java`

**Interfaces:**
- Consumes: Task 1's `Injection` names (as strings on the wire to the image; the harness module keeps its own enum).
- Produces: `record CredentialRoute(String upstreamOrigin, Injection injection, List<String> pathPrefixes)`,
  `enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }` (in `dev.codespire.harness`);
  `HarnessInvocation.credentialProxied()`; `HarnessAdapter.credentialRoute(HarnessInvocation)`;
  `CredentialRoute.AGENT_FACING_ORIGIN = "http://spire-model:8080"` (the harness module cannot see `spire-runtime`;
  Task 4 asserts it equals `RunUnitSpec.MODEL_PROXY_ADDRESS`). Codex constants: `PLACEHOLDER = "TEST-PLACEHOLDER"`, `PLACEHOLDER_VARIABLE = "SPIRE_MODEL_PLACEHOLDER"`,
  `PROVIDER_ID = "spire"`.

- [ ] **Step 1: Write the failing tests.**
  - `CodexAdapterTest.aProxiedApiKeyRunTalksToTheModelProxy`: argv script contains
    `-c model_provider="spire"`, `-c model_providers.spire.base_url="http://spire-model:8080/v1"`,
    `-c model_providers.spire.env_key="SPIRE_MODEL_PLACEHOLDER"`, `-c model_providers.spire.wire_api="responses"`,
    and no `codex login`.
  - `CodexAdapterTest.aProxiedSeatRunUsesAPlaceholderSignIn`: argv contains `chatgpt_base_url="http://spire-model:8080/backend-api/"`,
    `model_providers.spire.base_url="http://spire-model:8080/backend-api/codex"`, `requires_openai_auth=true`,
    `-c features.apps=false`; the environment's `CODEX_SIGN_IN_FILE` is the placeholder file.
  - `CodexAdapterTest.aProxiedRunsEnvironmentHoldsNoCredential`: for both payments, with credential
    `TEST-secret-key` / a signed-in file with `TEST-access`, no environment value and no argv element contains either.
  - `CodexAdapterTest.theRoutePerPayment`: API key → `("https://api.openai.com", BEARER, ["/v1/responses", "/v1/models"])`;
    seat → `("https://chatgpt.com", CHATGPT_SEAT, ["/backend-api/codex/", "/backend-api/wham/"])`; not proxied → empty.
  - `CodexAdapterTest.anUnproxiedRunIsUnchanged`: today's argv and environment, byte for byte, when `credentialProxied=false`.
  - `PlaceholderSignInTest`: the file parses as JSON with `auth_mode=chatgpt`, empty `refresh_token`, `account_id`
    `00000000-0000-4000-8000-000000000000`; each JWT payload has `exp=4102444800` and `spire_placeholder=true`.
  - `HarnessAdapterContract.aProxiedRunCarriesNoCredentialValue` (applies to every adapter that returns a route).
- [ ] **Step 2: Push; CI `fast tests` fails** — record the run id.
- [ ] **Step 3: Implement.** In `CodexAdapter`, when `invocation.credentialProxied()`: skip `login(...)` for the API
  key, write `PlaceholderSignIn.file()` for the seat through the existing `printenv CODEX_SIGN_IN_FILE` step, add the
  provider `-c` flags (quoted as today's `model` flag), keep `NO_EXTRA_CALLS` (+ `NO_APPS` for a seat), and map
  `SPIRE_MODEL_PLACEHOLDER=TEST-PLACEHOLDER` into `OWN_SETTINGS` for the API key. `environment()` then contains no
  `OPENAI_API_KEY`. `egressHosts` is unchanged. Comment at `credentialRoute`: which spike measured each path (2026-11-07).
- [ ] **Step 4: Push; CI `fast tests` passes.**
- [ ] **Step 5: Commit** (`Point Codex at the model proxy with a placeholder credential`).

### Task 4: The run worker builds proxied review units and reads their outcome

**Files:**
- Modify: `spire-run-worker/src/main/java/dev/codespire/runworker/RunUnitBuilder.java` (decide proxying in the private `build(..., Topology)` near :246–301)
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/CredentialProxyMode.java` (`enum OFF, REVIEWS, ALL` + `boolean appliesTo(boolean reviewRun)`; a review run is one built through `RunUnitBuilder.buildReview`)
- Modify: `spire-run-worker/src/main/resources/application.yml` (`spire.run.credential-proxy.mode: ${SPIRE_RUN_CREDENTIAL_PROXY_MODE:REVIEWS}`, `spire.run.credential-proxy.image: ${SPIRE_RUN_CREDENTIAL_PROXY_IMAGE:spire-model-proxy:latest}`)
- Modify: `spire-run-worker/.../RunLauncher.java` (`agentFailure` at :338: model proxy report before egress hosts)
- Modify: `.env.example`, `deploy/.env.example`, `deploy/compose*.yml`, `deploy/helm/...` values (the two variables, documented, no secret)
- Test: `spire-run-worker/src/test/.../RunUnitBuilderTest.java`, `RunLauncherModelProxyTest.java`

**Interfaces:**
- Consumes: `RunUnitSpec.withModelProxy`, `RunRuntime.modelProxyOf`, `ModelProxyReport` (Task 2);
  `HarnessAdapter.credentialRoute`, `HarnessInvocation.withCredentialProxied` (Task 3); image contract (Task 1).
- Produces: nothing new on the wire. `RunFailureCause.CREDENTIAL_REJECTED` and `BLOCKED_EGRESS` are reused.

- [ ] **Step 1: Write the failing tests.**
  - `RunUnitBuilderTest.aReviewRunIsProxied`: mode `REVIEWS`, egress `REPORT`, a REVIEW run on an API key → the spec
    has a model proxy whose environment holds `SPIRE_MODEL_SECRET=<the key>`, `SPIRE_MODEL_UPSTREAM=https://api.openai.com`,
    `SPIRE_MODEL_INJECTION=BEARER`; the agent's environment contains no value equal to the key.
  - `RunUnitBuilderTest.aSeatReviewRunHandsTheProxyTheSignedInFile`: `SPIRE_MODEL_SECRET` is the handed-over file,
    `SPIRE_MODEL_INJECTION=CHATGPT_SEAT`; the agent's `CODEX_SIGN_IN_FILE` is the placeholder.
  - `RunUnitBuilderTest.aFactoryBuildIsNotProxiedInReviewsMode` and `isProxiedInAllMode`.
  - `RunUnitBuilderTest.theHarnessAndTheRuntimeAgreeOnTheModelProxyAddress`:
    `CredentialRoute.AGENT_FACING_ORIGIN.equals(RunUnitSpec.MODEL_PROXY_ADDRESS)`.
  - `RunUnitBuilderTest.withEgressOffNoUnitIsProxied` (Review Focus 5): mode `REVIEWS`, egress `OFF` → no model proxy,
    today's agent environment.
  - `RunLauncherModelProxyTest.aRejectedInjectedKeyFailsAsCredentialRejected` (Review Focus 2): agent exits 1, report
    `rejected=1` → `RunFailed` cause `CREDENTIAL_REJECTED`.
  - `RunLauncherModelProxyTest.aRefusedModelPathFailsAsBlockedEgress` (Review Focus 1): report
    `refusedPaths=["/v1/files"]` → cause `BLOCKED_EGRESS`, detail contains `model path /v1/files refused`.
  - `RunLauncherModelProxyTest.aCleanReportChangesNothing`: `requests=3, rejected=0` → the adapter's own classification.
  Fakes override every `RunRuntime` method the path reaches (CLAUDE.md gotcha), with a comment why.
- [ ] **Step 2: Push; CI `service tests + packaging` fails** — record the run id.
- [ ] **Step 3: Implement.** Proxy a unit when `mode.appliesTo(reviewRun)`, `egressMode != OFF` and
  `adapter.credentialRoute(invocation.withCredentialProxied(true))` is present; then the agent invocation is the
  proxied one and the model proxy's environment is built from the route and the decrypted credential
  (`credentials.harnessEnv`). Log the WARN once at startup when mode is not `OFF` and egress is `OFF`.
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** (`Run review units with the credential behind the model proxy`).

### Task 5: Mutation check, docs, live acceptance

**Files:**
- Modify: `docs/factory/RUN-TOPOLOGY.md` (§6B, §7.1, §8: the model proxy, the measured paths, credentials), `docs/factory/EXECUTION-LAYER.md` §5.2, `docs/SECURITY.md` (the agent holds a placeholder on review runs), `docs/DECISIONS.md` ADR-050 "Step 3", `docs/UNVERIFIED.md` (new entry), `CLAUDE.md` (Build & run: four factory images; Status), `docs/HISTORY.md`, techdebt `techdebt/global/4-3-command-receives-credentials-it-must-never-use.md` (narrowed)
- Create: `docs/factory/HARNESS-REVIEWS-3-ACCEPTANCE.md`

- [ ] **Step 1: Mutation-verify the guards** on a scratch branch (one CI run per mutant, or one batched run):
  remove the header drop in `Injector` (`replacesWhateverCredentialTheAgentSent` must fail); remove the path check
  (`anUndeclaredPathIsRefusedWithoutReachingTheVendor`); flush only at the end (`aStreamIsPassedOnAsItArrives`); keep the
  proxy variables for the agent (`anAgentBehindTheModelProxyHasNoProxyVariables`); drop the `rejected` branch in
  `agentFailure` (`aRejectedInjectedKeyFailsAsCredentialRejected`). Record each in the PR body.
- [ ] **Step 2: Docs** as listed. UNVERIFIED gets: WebSocket upgrades and `*.oaiusercontent.com` never measured;
  `ALL` mode (factory builds) not proven live; Kubernetes arm unchanged.
- [ ] **Step 3: Live acceptance on the dev stack** (`spire-test`, images rebuilt from the branch, mode `REVIEWS`,
  egress `ENFORCE`):
  1. A review on an **API key**: posted review; `docker inspect` of the agent shows no key value in its environment;
     the model proxy log shows `POST /v1/responses -> 200` only; `run_egress` shows `api.openai.com` only.
  2. A review on the **seat**: as above with the `/backend-api/...` paths; charge at cost 0; `chatgpt.com` only.
  3. A disabled key (a `TEST-` value saved as a new API key): the run fails `CREDENTIAL_REJECTED`; the pool marks it.
  Record each run id, the commands and the outputs in `HARNESS-REVIEWS-3-ACCEPTANCE.md`. **No Claude Code subscription
  run.**
- [ ] **Step 4: Commit** (`Record the credential proxy and its live acceptance`); push; `review: requested`; delegate
  the review.

## Next plans (not in this PR)

- **PR 3b — Claude Code adapter (API key only).** `HarnessType.CLAUDE_CODE` adapter: `claude -p --output-format
  stream-json --verbose --model <m> --dangerously-skip-permissions` as a non-root user, prompt on stdin, environment
  `ANTHROPIC_BASE_URL=http://spire-model:8080`, `ANTHROPIC_API_KEY=<placeholder>`,
  `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`; route `("https://api.anthropic.com", X_API_KEY, ["/v1/messages"])`;
  usage from the `result` event (input, output, cache creation, cache read; `total_cost_usd` as a cross-check).
  **The subscription mode is kept as a disabled branch**: present in the code with a comment that Anthropic's terms do
  not allow it and that it is kept for history only (with the doc link above), not offered in the UI, and refused by
  the server (a sign-in or a run request for it is rejected, not silently changed). No subscription test is run.
- **PR 3c — opencode adapter (API keys).** `opencode run -m <provider/model>`, `opencode.json` with each provider's
  `baseURL` at `spire-model` and a placeholder `apiKey`, `OPENCODE_DISABLE_MODELS_FETCH=1`; routes per provider.
  opencode with a subscription is not built.
- **Step 8 — fork pull requests**, once this PR is accepted for Codex on both payments.
