# Harness reviews, PR 3: the agent holds a placeholder, a proxy adds the credential — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In a proxied run unit the agent container holds no usable model credential and has no route out. It talks
plain HTTP to a per-run **model proxy** (`spire-model`), which holds the real API key or seat token, adds it to each
model request and forwards it over HTTPS through the step 2 egress proxy, in `ENFORCE` mode. A key the vendor refuses
(401 on the model call) fails the run as `CREDENTIAL_REJECTED` and marks the pool member; a model path the harness did
not declare fails it as `BLOCKED_EGRESS`.

**Architecture:** A proxied unit has **two** internal networks. The agent network (`spire-run-<id>-agent`) holds the
agent and the model proxy only. A new model network (`spire-run-<id>-model`, also `--internal`, isolated gateway) holds
the model proxy and the egress proxy only; the egress proxy keeps its outbound bridge. So the agent's only reachable
peer is `spire-model`, and only the model proxy can reach `spire-egress`. The model proxy is a small Java 25 service
(our image `spire-model-proxy`, built like the publisher). The route (vendor origin, injection kind, allowed paths) comes
from the harness adapter (`HarnessAdapter.credentialRoute`), never from the repository or the ticket. Codex is
configured by the adapter: a custom model provider pointing at `spire-model`, plus a placeholder `auth.json` for a seat.

**Tech Stack:** Java 25 (`com.sun.net.httpserver`, `java.net.http.HttpClient`), docker-java (`spire-runtime-docker`),
Quarkus run-worker, Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` — "Final design review" (the agent must never
hold a usable credential; a credential-injecting proxy adds it; placeholder in the agent), delivery step 3, and "After
step 2" (step 3 is built right before step 8; the step 3 measurements and the items "not settled without real
credentials"). Live spike of 2026-11-07/08 (below).

## Global Constraints

- The agent container never receives a usable model credential in a proxied unit: not in its environment, argv,
  mounts, files or `HarnessInvocation`. Only placeholders (`TEST-PLACEHOLDER`-style constants defined in code;
  placeholder JWTs are generated at run time, never written as a literal `eyJ…` anywhere, gitleaks' JWT rule).
- The route (upstream origin, injection kind, allowed paths) comes from the harness adapter, never from the reviewed
  repository, the ticket or the prompt.
- The model proxy logs method, normalized path, status and duration only. Never a header value, a body, a query or an
  exception message (one can carry the URI).
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

The agent image pins codex-cli 0.156.1 (`deploy/agent/codex/Dockerfile:24`). Codex extra calls stay off
(`features.plugins=false`, `analytics.enabled=false`, seat: `features.apps=false`). The seat injection needs
`Authorization: Bearer <access_token>` and `chatgpt-account-id: <account_id>`; plain HTTP inside the unit works, no TLS
termination. Not seen in any run: the `*.oaiusercontent.com` hosts from step 2 and WebSocket upgrades.

## Decisions (designer's calls; revised after the plan check, see the last section)

- **Scope.** This PR: the model proxy, the runtime wiring, Codex on both payments, and the first producer of
  `CREDENTIAL_REJECTED`. Claude Code (API key, with the subscription mode as a disabled branch) is **PR 3b**; opencode
  (API keys) is **PR 3c**; each its own plan. Fork reviews stay refused until step 8; this PR changes no fork gate.
- **Topology.** Two internal networks per proxied unit (Architecture). A proxied unit's egress proxy always runs in
  `ENFORCE` with the adapter's `egressHosts`, whatever `spire.run.egress.mode` says (the hosts are measured); an
  unproxied unit keeps the global mode.
- **Which runs.** `spire.run.credential-proxy.mode` = `OFF` | `REVIEWS` | `ALL`, default `REVIEWS` (units built through
  `RunUnitBuilder.buildReview`). `ALL` adds factory builds later, once measured there. It needs an egress network: with
  `spire.run.egress.mode=off` no unit is proxied and the worker logs one WARN at startup naming both settings. When the
  mode is not `OFF`, a startup check inspects the model proxy image (like `PublisherImageCheck`) and refuses to start
  the lane with a message naming `SPIRE_RUN_CREDENTIAL_PROXY_IMAGE` when it is missing (the image is not on GHCR yet).
- **Injection kinds** (`Injection`): `BEARER` (set `Authorization: Bearer <key>`), `X_API_KEY` (set `x-api-key`; for PR
  3b/3c, unit-tested here), `CHATGPT_SEAT` (`Authorization: Bearer <access_token>`, `chatgpt-account-id: <account_id>`).
  Every incoming `authorization`, `x-api-key`, `chatgpt-account-id`, `cookie` and `proxy-authorization` header is
  dropped first. There is deliberately no Anthropic OAuth kind.
- **What the model proxy receives.** The key; for a seat only `access_token` and `account_id` (the builder extracts
  them from the handed-over file), never the whole `auth.json` (its `id_token` carries email claims and
  `docker inspect` prints the environment).
- **The adapter never sees the real secret** in a proxied unit: the builder replaces each credential value in
  `HarnessInvocation.credentials` with a placeholder of the same key name before calling the adapter, so the adapter
  still knows seat vs key and cannot leak what it does not have.
- **Allowed paths**, matched on the normalized path. A request whose raw path contains `..`, `%2e`, `%2f`, `%5c`, `//`
  or `\` is refused before matching. API key: exactly `/v1/responses` or `/v1/models`. Seat: prefixes
  `/backend-api/codex/` and `/backend-api/wham/`. Any other path, any absolute-form or CONNECT request, and any
  WebSocket upgrade get `403`/`501` without contacting the vendor, logged as `refused`.
- **Responses.** Hop-by-hop headers, `set-cookie` and `www-authenticate` are not passed back. A 401 or 403 body is
  replaced by the fixed body `{"error":{"message":"spire-model: the vendor refused the credential"}}` (OpenAI's 401
  echoes part of the key; the project drops such bodies elsewhere, `HttpFailures`).
- **Outcome reading.** stdout line per request: `spire-model <METHOD> <path> -> <status|refused>`. The runtime reads it
  after salvage, before destroy (where the egress log is read), into
  `ModelProxyReport(int requests, int rejected, List<String> refusedPaths)`. **`rejected` counts 401 only, and only on
  the model call path** (`/v1/responses`, `/backend-api/codex/responses`): a 403 can be a Cloudflare challenge or an
  entitlement answer, and marking would retire a healthy seat for good (`RunCredentialFeedback` keeps a marked member
  out until an operator replaces it). In `RunLauncher`, `rejected > 0` becomes `CREDENTIAL_REJECTED`; refused paths
  become `BLOCKED_EGRESS` with `model path <path> refused`; a 403 stays the adapter's failure with the path in the
  detail. This is the first producer of `CREDENTIAL_REJECTED` (Codex's exit 120 maps to `HARNESS_EXIT_NONZERO`,
  `CodexAdapter.java:412-417`; the spec's claim otherwise is corrected here).
- **HTTP client.** `HttpClient.Version.HTTP_1_1`, redirects `NEVER`, connect timeout 10 s, `ProxySelector` from
  `HTTPS_PROXY`. Request body forwarded with its `Content-Length` when present
  (`BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(...), length)`), else streamed. Restricted headers never
  copied: `connection`, `content-length`, `expect`, `host`, `upgrade`, plus hop-by-hop.
- **Corporate trust.** The JVM ignores `SSL_CERT_FILE` (`CorporateTransport.java:28-40`). The model proxy gets the
  unit's host mounts read-only and builds its trust store from the bundle named by `SSL_CERT_FILE`, reusing
  `CorporateTransport`'s bundle logic (moved to a JDK-only shared class in `spire-secrets`'s style, or copied with a
  pointer comment if a move breaks the publisher's packaging).
- **Environment ownership.** The model proxy's environment is built by `RunUnitSpec.environmentFor` like every other
  container: it gets what the proxied agent got before (trust environment, the egress proxy variables, empty
  `NO_PROXY`); the agent of a proxied unit gets the trust environment only. The collision check covers the new
  container; it takes no mounts other than the host trust mounts and no `UnitInputs`.
- **Image.** `spire-model-proxy:latest`, config `spire.run.credential-proxy.image`
  (`SPIRE_RUN_CREDENTIAL_PROXY_IMAGE`). FSL-1.1-ALv2 like the publisher (it is a deployable). Built locally like the
  publisher; four factory images in the docs. GHCR (`docker.yml` matrix) is a follow-up.

## Review Focus

1. A Codex upgrade starts calling a path the route does not allow: the run must fail visibly as `BLOCKED_EGRESS`
   naming the path. (Task 4.)
2. The vendor refuses the injected key or seat: a 401 on the model call ends `CREDENTIAL_REJECTED` and marks the pool
   member; a 403 does not. (Tasks 1 and 4.)
3. A streamed model answer that takes minutes passes each chunk on as it arrives. (Task 1.)
4. The real credential appears nowhere the agent can read and in no log line, for both payments, including dot-segment
   tricks against the allowed paths. (Tasks 1, 3, 4.)
5. `credential-proxy.mode=REVIEWS` with `egress.mode=off`, or with the image missing: the run still runs as today, or
   the lane refuses at startup with a clear message. (Task 4.)

---

### Task 1: The model proxy service and image

**Files:**
- Create: `spire-model-proxy/build.gradle.kts`, `spire-model-proxy/Dockerfile`, `spire-model-proxy/.dockerignore`, `spire-model-proxy/LICENSE` (FSL-1.1-ALv2, as `spire-publisher/LICENSE`)
- Create: `spire-model-proxy/src/main/java/dev/codespire/modelproxy/ModelProxy.java` (main, server, forwarding)
- Create: `.../modelproxy/ProxyRoute.java`, `.../modelproxy/Injector.java`, `.../modelproxy/PathGuard.java`, `.../modelproxy/TrustBundle.java`
- Modify: `settings.gradle.kts`; root `build.gradle.kts` module lists (as `spire-secrets`, ~line 221); root `Dockerfile` COPY line (`ImageBuildSeesEveryModuleTest`); `LICENSING.md`; `spire-arch/.../NoCorporateEnvironmentIsBakedIntoAnImageTest.java` (`RUN_UNIT_IMAGES` += `spire-model-proxy/Dockerfile`)
- Test: `spire-model-proxy/src/test/java/dev/codespire/modelproxy/ModelProxyTest.java`, `InjectorTest.java`, `PathGuardTest.java`, `ProxyRouteTest.java`, `TrustBundleTest.java`

**Interfaces:**
- Produces (environment contract of the image):
  - `SPIRE_MODEL_UPSTREAM` — origin, e.g. `https://chatgpt.com`
  - `SPIRE_MODEL_INJECTION` — `BEARER` | `X_API_KEY` | `CHATGPT_SEAT`
  - `SPIRE_MODEL_PATHS_EXACT`, `SPIRE_MODEL_PATHS_PREFIX` — comma-separated (either may be empty, not both)
  - `SPIRE_MODEL_SECRET` — the key, or the seat's access token
  - `SPIRE_MODEL_ACCOUNT` — the seat's account id (`CHATGPT_SEAT` only)
  - `SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM` — `true` only in tests; otherwise the upstream must be `https`
  - `HTTPS_PROXY`, `SSL_CERT_FILE` — optional
  - listens on `8080`; stdout `spire-model <METHOD> <path> -> <status>` or `-> refused`
- Produces: `record ProxyRoute(URI upstream, Injection injection, Set<String> exactPaths, List<String> pathPrefixes)`
  with `static ProxyRoute fromEnvironment(Map<String, String> env)`; `enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }`;
  `static Optional<String> PathGuard.allowed(String rawPath, ProxyRoute route)` (the normalized path, or empty);
  `static Map<String, List<String>> Injector.outgoing(Map<String, List<String>> incoming, Injection kind, String secret, String account)`.

- [ ] **Step 1: Write the failing tests.** `ModelProxyTest` runs the proxy in-process against a JDK `HttpServer` upstream
  on `127.0.0.1` (`SPIRE_MODEL_ALLOW_PLAINTEXT_UPSTREAM=true`) and asserts:
  - `replacesWhateverCredentialTheAgentSent`: agent sends `Authorization: Bearer TEST-PLACEHOLDER` and a `cookie`;
    upstream sees exactly one `authorization: Bearer TEST-secret-key` and no `cookie`.
  - `aSeatSendsTheAccessTokenAndTheAccountId`: upstream sees `Bearer TEST-access` and `chatgpt-account-id: TEST-account`,
    even when the agent sent its own `chatgpt-account-id`.
  - `anXApiKeyRouteSendsNoAuthorization`.
  - `anUndeclaredPathIsRefusedWithoutReachingTheVendor`: `GET /v1/files` → 403, upstream hits 0, stdout `-> refused`.
  - `aDotSegmentCannotEscapeAnAllowedPath`: `/v1/responses/../files`, `/v1/responses%2F..%2Ffiles`,
    `/v1/%2e%2e/files`, `//v1/responses`, `/v1\responses` → each 403, upstream hits 0.
  - `anApiKeyRouteMatchesExactly`: `/v1/responses/extra` → 403; `/v1/responses?x=1` → forwarded.
  - `aWebSocketUpgradeIsRefused`: `Upgrade: websocket` → 501, upstream hits 0.
  - `aStreamIsPassedOnAsItArrives`: upstream writes `data: TEST-1\n\n`, then waits on a latch; the client reads it before
    the latch is released.
  - `aRefusedCredentialsBodyAndChallengeAreReplaced`: upstream 401 with body `TEST-sk-echo` and
    `www-authenticate`/`set-cookie` headers → client gets 401, the fixed body, neither header.
  - `theLogNamesNoSecretNoQueryAndNoException`: `POST /v1/responses?key=TEST-q` → stdout contains
    `spire-model POST /v1/responses -> 200` and none of `TEST-secret-key`, `TEST-access`, `TEST-q`; an upstream that
    resets the connection logs `-> 502` and no exception text.
  - `aContentLengthIsKept`: a 3-byte POST arrives upstream with `content-length: 3`, not chunked.
  `ProxyRouteTest`: each missing/blank variable, an unknown injection, a seat without `SPIRE_MODEL_ACCOUNT`, an `http`
  upstream without the plaintext flag, and empty path lists each throw naming the variable (and no value).
  `TrustBundleTest.aBundleInSslCertFileIsTrusted`: a self-signed PEM bundle → the built `SSLContext` trusts that
  certificate; no bundle → the JDK default.
- [ ] **Step 2: Push; CI `fast tests` fails** (compile) — record the run id.
- [ ] **Step 3: Implement** per the Decisions (HTTP client, paths, responses, logging). `HttpServer` on `0.0.0.0:8080`,
  virtual-thread executor; response copied in 8 KiB writes with a flush after each, `sendResponseHeaders(status, 0)`.
  Exit non-zero at start on an invalid route, naming the variable only. `Dockerfile`: `eclipse-temurin:25-jre-alpine`,
  the `APK_UPGRADE_BUST` layer, uid 1002, `EXPOSE 8080`,
  `ENTRYPOINT ["java","-XX:-UsePerfData","-XX:MaxRAMPercentage=75","-cp","/opt/spire-model-proxy/lib/*","dev.codespire.modelproxy.ModelProxy"]`.
- [ ] **Step 4: Push; CI `fast tests` passes** (incl. `spire-arch`).
- [ ] **Step 5: Commit** (`Add the model proxy that injects a run's credential`).

### Task 2: The runtime starts the model proxy on its own networks

**Files:**
- Modify: `spire-runtime/.../RunUnitSpec.java` (component `ContainerSpec modelProxy`, nullable, after `egress`; wither `withModelProxy`; existing constructors pass `null`; `environmentFor`/`deploymentEnvironment` per "Environment ownership"; `requireNoEnvironmentCollision` and the mount/input checks cover it; a model proxy with egress `OFF` throws)
- Create: `spire-runtime/.../ModelProxyReport.java`
- Modify: `spire-runtime/.../RunRuntime.java` (`default ModelProxyReport modelProxyOf(RunHandle handle) { return ModelProxyReport.NONE; }`)
- Create: `spire-runtime-docker/.../ModelProxyContainer.java`, `ModelProxyLogParser.java`
- Modify: `spire-runtime-docker/.../DockerRunNetworks.java` (the model network; egress proxy joins agent network only when unproxied, the model network when proxied; model proxy joins agent + model networks, alias `spire-model`)
- Modify: `spire-runtime-docker/.../DockerRunRuntime.java` (create after `networks.open`; `cancel` (:812) and `stopProxy` stop the new role; `modelProxyOf`; the egress policy of a proxied unit is forced to `ENFORCE` with the spec's hosts)
- Test: `RunUnitSpecTest.java`, `ModelProxyLogParserTest.java`, `DockerRunRuntimeIT.java`

**Interfaces:**
- Consumes: Task 1's image and environment contract.
- Produces: `RunUnitSpec.withModelProxy(ContainerSpec)`, `RunUnitSpec.MODEL_PROXY_ALIAS = "spire-model"`,
  `RunUnitSpec.MODEL_PROXY_ADDRESS = "http://spire-model:8080"`;
  `record ModelProxyReport(int requests, int rejected, List<String> refusedPaths)` with `static final ModelProxyReport NONE`
  and `static final Set<String> MODEL_CALL_PATHS = Set.of("/v1/responses", "/backend-api/codex/responses")`;
  `RunRuntime.modelProxyOf(RunHandle)`.

- [ ] **Step 1: Write the failing tests.**
  - `RunUnitSpecTest.aProxiedAgentHasNoProxyVariablesAndTheModelProxyHasThem`; `anUnproxiedAgentKeepsThem`;
    `aModelProxyNeedsAnEgressNetwork`; `aModelProxyMayNotCollideWithTheDeploymentEnvironment`;
    `aModelProxyTakesNoWorkspaceOrHandoffMount`.
  - `ModelProxyLogParserTest`: `POST /v1/responses -> 401` counts as rejected; `GET /backend-api/wham/accounts/check -> 401`
    and `POST /v1/responses -> 403` do not; `-> refused` adds the path; other lines ignored; 8 MiB / 256-entry caps.
  - `DockerRunRuntimeIT` (builds `spire-model-proxy` in `@BeforeAll`; a plain-HTTP stub upstream container with the
    test-only flag):
    - `theModelProxyJoinsTheAgentAndModelNetworksOnly` and `theEgressProxyIsNotOnTheAgentNetwork`.
    - `theAgentReachesOnlyTheModelProxy`: from the agent `http://spire-model:8080/v1/responses` answers;
      `http://spire-egress:8888` does not resolve or connect; an outside host is unreachable.
    - `aProxiedUnitEnforcesEvenWhenTheGlobalModeIsReport`: a host outside the route is refused for the model proxy.
    - `theModelProxysTrafficIsRecordedByTheEgressProxy`.
    - `destroyRemovesTheModelProxyAndBothNetworks`; `cancelStopsTheModelProxy`.
    - `theReportCountsARejectedModelCall`: stub answers 401 on `/v1/responses` → `rejected() == 1`.
- [ ] **Step 2: Push; CI shows the failures** — record the run ids.
- [ ] **Step 3: Implement.** `ModelProxyContainer` mirrors `EgressProxyContainer`: 192 MiB memory (a JVM), 0.5 CPU,
  64 pids, read-only root, `/tmp` tmpfs `size=8m`, `CapDrop ALL`, no-new-privileges, the spec's host trust mounts
  read-only; waited for by probing `/proc/net/tcp` for 8080. Its environment comes from `spec.environmentFor(modelProxy)`.
- [ ] **Step 4: Push; CI `service tests + packaging` passes.**
- [ ] **Step 5: Commit** (`Start a model proxy on its own network beside the agent`).

### Task 3: The harness declares its credential route; Codex uses it

**Files:**
- Create: `spire-harness/.../CredentialRoute.java`
- Modify: `spire-harness/.../HarnessAdapter.java` (`default Optional<CredentialRoute> credentialRoute(HarnessInvocation invocation) { return Optional.empty(); }`)
- Modify: `spire-harness/.../HarnessInvocation.java` (component `boolean credentialProxied`, last; convenience constructors pass `false`; wither `withCredentialProxied(boolean)` enumerating every component)
- Modify: `spire-harness-codex/.../CodexAdapter.java`; create `PlaceholderSignIn.java` in the same package
- Modify: `spire-harness/src/testFixtures/.../HarnessAdapterContract.java`
- Test: `CodexAdapterTest.java`, `PlaceholderSignInTest.java`

**Interfaces:**
- Produces: `record CredentialRoute(String upstreamOrigin, Injection injection, Set<String> exactPaths, List<String> pathPrefixes)`,
  `enum Injection { BEARER, X_API_KEY, CHATGPT_SEAT }` (in `dev.codespire.harness`; names equal Task 1's),
  `CredentialRoute.AGENT_FACING_ORIGIN = "http://spire-model:8080"` (the harness module cannot see `spire-runtime`;
  Task 4 asserts it equals `RunUnitSpec.MODEL_PROXY_ADDRESS`); `HarnessInvocation.credentialProxied()`;
  `HarnessAdapter.credentialRoute(HarnessInvocation)`. Codex constants: `PLACEHOLDER = "TEST-PLACEHOLDER"`,
  `PLACEHOLDER_VARIABLE = "SPIRE_MODEL_PLACEHOLDER"`, `PROVIDER_ID = "spire"`.

- [ ] **Step 1: Write the failing tests.**
  - `aProxiedApiKeyRunTalksToTheModelProxy`: argv script contains `-c model_provider="spire"`,
    `-c model_providers.spire.base_url="http://spire-model:8080/v1"`, `-c model_providers.spire.env_key="SPIRE_MODEL_PLACEHOLDER"`,
    `-c model_providers.spire.wire_api="responses"`, no `codex login`.
  - `aProxiedSeatRunUsesAPlaceholderSignIn`: argv contains `chatgpt_base_url="http://spire-model:8080/backend-api/"`,
    `model_providers.spire.base_url="http://spire-model:8080/backend-api/codex"`, `requires_openai_auth=true`,
    `-c features.apps=false`; `CODEX_SIGN_IN_FILE` is `PlaceholderSignIn.file()`.
  - `aProxiedRunsEnvironmentHoldsOnlyPlaceholders`: with placeholder credentials in, every environment value is a
    placeholder or a fixed setting (no `OPENAI_API_KEY`).
  - `theRoutePerPayment`: API key → `("https://api.openai.com", BEARER, {"/v1/responses","/v1/models"}, [])`;
    seat → `("https://chatgpt.com", CHATGPT_SEAT, {}, ["/backend-api/codex/","/backend-api/wham/"])`; not proxied → empty.
  - `anUnproxiedRunIsUnchanged`: today's argv and environment byte for byte when `credentialProxied=false`.
  - `PlaceholderSignInTest`: JSON with `auth_mode=chatgpt`, empty `refresh_token`, `account_id`
    `00000000-0000-4000-8000-000000000000`; each JWT payload `exp=4102444800`, `spire_placeholder=true`; built at run
    time from claims, so no JWT literal exists in the repository.
  - `HarnessAdapterContract.aProxiedRunNeverNeedsTheRealSecret` (every adapter returning a route): proxied
    command/environment are identical whether the credential map holds a real-looking value or its placeholder.
- [ ] **Step 2: Push; CI `fast tests` fails** — record the run id.
- [ ] **Step 3: Implement.** When `credentialProxied()`: no `login(...)` for the API key; the seat writes
  `PlaceholderSignIn.file()` through the existing `printenv CODEX_SIGN_IN_FILE` step; add the provider `-c` flags
  (quoted like `--model`); keep `NO_EXTRA_CALLS` (+ `NO_APPS` for a seat); `SPIRE_MODEL_PLACEHOLDER=TEST-PLACEHOLDER`
  joins `OWN_SETTINGS` for the API key. `egressHosts` unchanged. A comment at `credentialRoute` cites the 2026-11-07
  spike for each path.
- [ ] **Step 4: Push; CI `fast tests` passes.**
- [ ] **Step 5: Commit** (`Point Codex at the model proxy with a placeholder credential`).

### Task 4: The run worker builds proxied review units; a refused key reaches the pool

**Files:**
- Modify: `spire-run-worker/.../RunUnitBuilder.java` (private `build(..., Topology)` near :246–301: placeholder credentials into the invocation; the model proxy's environment from the route and the real credential; seat → `access_token`/`account_id` via `SignInFiles`-style extraction)
- Create: `spire-run-worker/.../CredentialProxyMode.java` (`enum OFF, REVIEWS, ALL`; `boolean appliesTo(boolean reviewRun)`)
- Create: `spire-run-worker/.../ModelProxyImageCheck.java` (like `PublisherImageCheck`)
- Modify: `spire-run-worker/.../RunLauncher.java` (read `modelProxyOf` where `EgressResults.read` is, on the salvaged AND the `unobserved` path :244–267; thread it through `Observed`; `agentFailure` :338: rejected → `CREDENTIAL_REJECTED`, refused paths → `BLOCKED_EGRESS`, then egress hosts)
- Modify: `spire-run-worker/src/main/resources/application.yml` (`spire.run.credential-proxy.mode: ${SPIRE_RUN_CREDENTIAL_PROXY_MODE:REVIEWS}`, `.image: ${SPIRE_RUN_CREDENTIAL_PROXY_IMAGE:spire-model-proxy:latest}`); `.env.example`, `deploy/.env.example`, `deploy/compose*.yml`, Helm values
- Delete: `spire-arch/src/test/java/dev/codespire/arch/CredentialRefusalHasNoProducerTest.java`
- Modify: `spire-orchestrator/.../factory/RunCredentialFeedback.java` and `HarnessCredentialPool.java` javadoc; `techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md` (as the guard's message demands)
- Test: `RunUnitBuilderTest.java`, `RunLauncherModelProxyTest.java`, `spire-orchestrator/.../factory/RunResultSagaTest.java`

**Interfaces:**
- Consumes: Task 2 (`withModelProxy`, `modelProxyOf`, `ModelProxyReport`), Task 3 (`credentialRoute`,
  `withCredentialProxied`, `AGENT_FACING_ORIGIN`), Task 1 (image contract).
- Produces: nothing new on the wire. `CREDENTIAL_REJECTED` gets its first producer.

- [ ] **Step 1: Write the failing tests.**
  - `RunUnitBuilderTest.aReviewRunIsProxied`: mode `REVIEWS`, egress `REPORT`, an API-key REVIEW run → model proxy
    environment holds `SPIRE_MODEL_SECRET=<key>`, `SPIRE_MODEL_UPSTREAM=https://api.openai.com`, `SPIRE_MODEL_INJECTION=BEARER`,
    no plaintext flag; neither the agent's environment nor the `HarnessInvocation` given to the adapter holds the key.
  - `aSeatReviewRunHandsTheProxyOnlyTheTokenAndAccount`: `SPIRE_MODEL_SECRET`=access token, `SPIRE_MODEL_ACCOUNT`=account
    id, no environment value contains the `id_token` or the whole file.
  - `aFactoryBuildIsNotProxiedInReviewsMode`; `isProxiedInAllMode`; `withEgressOffNoUnitIsProxied` (Review Focus 5).
  - `theHarnessAndTheRuntimeAgreeOnTheModelProxyAddress`.
  - `ModelProxyImageCheckTest.aMissingImageStopsTheLaneNamingTheVariable` (Review Focus 5); `modeOffChecksNothing`.
  - `RunLauncherModelProxyTest` — its fake `RunRuntime` overrides `modelProxyOf` (comment: the default returns `NONE`, so
    an un-overridden fake passes silently, the CLAUDE.md gotcha):
    `aRejectedModelCallFailsAsCredentialRejected` (Review Focus 2); `aForbiddenAnswerIsNotARejectedCredential` (403 →
    the adapter's cause, path in the detail); `aRefusedModelPathFailsAsBlockedEgress` (Review Focus 1);
    `theReportIsReadWhenSalvageFails`; `aCleanReportChangesNothing`.
  - `RunResultSagaTest.aReviewRunsRefusedCredentialMarksThePoolMember`: a REVIEW run's `RunFailed` with
    `CREDENTIAL_REJECTED` → the member named by `factory_run.harness_credential_id` is marked rejected.
- [ ] **Step 2: Push; CI fails** — record the run ids.
- [ ] **Step 3: Implement.** Proxy a unit when `mode.appliesTo(reviewRun)`, egress mode is not `OFF` and
  `adapter.credentialRoute(placeholderInvocation.withCredentialProxied(true))` is present. WARN once at startup when the
  mode is not `OFF` and egress is `OFF`. Delete the guard and update the three documents in the same commit.
- [ ] **Step 4: Push; CI green.**
- [ ] **Step 5: Commit** (`Run review units with the credential behind the model proxy`).

### Task 5: Mutation check, docs, live acceptance

**Files:**
- Modify: `docs/factory/RUN-TOPOLOGY.md` §6B/§7.1/§8, `docs/factory/EXECUTION-LAYER.md` §5.2, `docs/SECURITY.md`,
  `docs/DECISIONS.md` ADR-050 "Step 3", `docs/superpowers/specs/2026-10-30-harness-reviews-design.md` (correct the
  exit-120 claim, line ~221), `docs/UNVERIFIED.md`, `CLAUDE.md` (four factory images; Status), `docs/HISTORY.md`,
  `techdebt/global/4-3-command-receives-credentials-it-must-never-use.md` (narrowed: proxied units)
- Create: `docs/factory/HARNESS-REVIEWS-3-ACCEPTANCE.md`

- [ ] **Step 1: Mutation-verify the guards** (scratch branch, CI): drop the header removal (`replacesWhateverCredentialTheAgentSent`);
  drop normalization (`aDotSegmentCannotEscapeAnAllowedPath`); flush only at the end (`aStreamIsPassedOnAsItArrives`);
  keep proxy variables for the agent (`aProxiedAgentHasNoProxyVariablesAndTheModelProxyHasThem`); put the egress proxy
  back on the agent network (`theAgentReachesOnlyTheModelProxy`); count 403 as rejected
  (`aForbiddenAnswerIsNotARejectedCredential`); pass the real key to the adapter (`aReviewRunIsProxied`). Record each in
  the PR body.
- [ ] **Step 2: Docs** as listed. UNVERIFIED: WebSocket upgrades and `*.oaiusercontent.com` never measured; `ALL` mode
  not proven live; Kubernetes arm unchanged; the image not on GHCR.
- [ ] **Step 3: Live acceptance on the dev stack** (`spire-test`, images rebuilt from the branch, mode `REVIEWS`,
  global egress `report` — the proxied unit must still enforce):
  1. A review on an **API key**: posted; `docker inspect` of the agent shows no key value; `docker network inspect`
     shows the agent network without `spire-egress`; the model proxy log shows `POST /v1/responses -> 200` only;
     `run_egress` shows `api.openai.com` only.
  2. A review on the **seat**: as above with the `/backend-api/...` paths; charge at cost 0; `chatgpt.com` only; the
     model proxy's environment holds the access token and account id, not `id_token`.
  3. A long seat review that compacts (a large PR): no new path or host appears (spec item "which paths long runs add").
  4. Note for each run whether any redirect or `oaiusercontent.com` attempt appears (spec items still unsettled).
  5. A rejected key (a `TEST-` value saved as a new API key, alone in the pool): the run fails `CREDENTIAL_REJECTED`; the
     pool shows the member rejected.
  Record run ids, commands and outputs in `HARNESS-REVIEWS-3-ACCEPTANCE.md`. **No Claude Code subscription run.**
- [ ] **Step 4: Commit** (`Record the credential proxy and its live acceptance`); push; `review: requested`; delegate
  the review to the code-review pane.

## Next plans (not in this PR)

- **PR 3b — Claude Code adapter (API key only).** `HarnessType.CLAUDE_CODE` adapter: `claude -p --output-format
  stream-json --verbose --model <m> --dangerously-skip-permissions` as a non-root user, prompt on stdin, environment
  `ANTHROPIC_BASE_URL=http://spire-model:8080`, `ANTHROPIC_API_KEY=<placeholder>`,
  `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`; route `("https://api.anthropic.com", X_API_KEY, {"/v1/messages"}, [])`;
  usage from the `result` event (input, output, cache creation, cache read; `total_cost_usd` as a cross-check).
  **The subscription mode is kept as a disabled branch** (operator's decision, 2026-11-08): present in the code with a
  comment that Anthropic's terms do not allow it and that it is kept for history only (with the doc link above), not
  offered in the UI, and refused by the server. No subscription test is run. (The plan check suggested leaving even the
  disabled branch out; the operator chose to keep it.)
- **PR 3c — opencode adapter (API keys).** `opencode run -m <provider/model>`, `opencode.json` with each provider's
  `baseURL` at `spire-model` and a placeholder `apiKey`, `OPENCODE_DISABLE_MODELS_FETCH=1`; routes per provider.
  opencode with a subscription is not built.
- **Step 8 — fork pull requests**, once this PR is accepted for Codex on both payments.
- **GHCR** for `spire-model-proxy` (`docker.yml` matrix).

## Changes after the plan check (Fable review, 2026-11-08)

1. HIGH — the agent could still reach `spire-egress` on the shared internal network (`DockerRunNetworks.java:89-94`,
   tinyproxy `Allow <subnet>`): a second internal network for model proxy + egress proxy; proxied units always enforce.
2. HIGH — `CREDENTIAL_REJECTED` had no producer and `CredentialRefusalHasNoProducerTest` guards that: the guard is
   retired with its three documents and a saga test; the spec's exit-120 claim is corrected.
3. HIGH — a 403 would retire a healthy seat for good: only a 401 on the model call path counts as rejected.
4. MEDIUM — the JVM ignores `SSL_CERT_FILE`: host trust mounts plus a trust store built from the bundle.
5. MEDIUM — the model proxy's environment now goes through `RunUnitSpec.environmentFor` and the collision checks.
6. MEDIUM — dot-segment and encoded path escapes refused; API-key paths match exactly; 401/403 bodies, `set-cookie`
   and `www-authenticate` are not passed back.
7. MEDIUM — module registration (root Dockerfile, LICENSE, `RUN_UNIT_IMAGES`, `LICENSING.md`) and a startup image check.
8. MEDIUM — plaintext upstream only behind an explicit test-only flag the builder never sets.
9. MEDIUM — the adapter gets placeholders, not the real secret; the seat proxy gets only the access token and account id.
10. MEDIUM — HTTP client pinned: HTTP/1.1, `Content-Length` kept, restricted headers listed, no PerfData, no exception
    text in logs.
11. LOW — the report is read on the salvage-failure path too; `cancel` stops the new role; the fake overrides
    `modelProxyOf`; the spec's unsettled items become live checks; placeholder JWTs are generated, never literal.
