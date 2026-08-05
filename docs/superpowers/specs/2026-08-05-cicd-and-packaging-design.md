# CI/CD and packaging — design

**Date:** 2026-08-05
**Status:** approved after adversarial review, not yet implemented
**Supersedes the "open decisions" table in** [`docs/CICD-AND-PACKAGING.md`](../../CICD-AND-PACKAGING.md) **§7.**

## Goal

Give this repository the two things it has never had: **verification that runs somewhere other than
one workstation**, and **artifacts somebody else can run**. Concretely — nine GitHub Actions
workflows plus a Dependabot config, four production images published to GHCR, and a `deploy/` tree
covering Docker Compose, Helm and kustomize from a single source of truth.

The analysis behind most of this already exists in `docs/CICD-AND-PACKAGING.md` and is not repeated.
That document was written on 2026-08-03, one day before D10 (operator authentication) landed. This
spec records what was decided on top of it, and — more importantly — **what D10 added to the job**
that the analysis predates.

An adversarial review of the first draft of this spec found four blocking defects in its deployment
half. All four are corrected here, and each correction is marked **[review]** so the reasoning is not
lost. The review is the reason this document is worth more than the draft was.

## What D10 changed

`CICD-AND-PACKAGING.md` §6 treats D10 purely as a gate to be cleared. It is also new scope.

**ADR-022's security mechanism is a property of the deployment topology, not of the code.** Each
service owns one URL prefix (`/api`, `/gw`, `/wk`) and scopes its session cookie to that path, so one
service can never receive another's credential. Cookies scope by host **and** path — which only
isolates anything if all four services answer on **one origin**.

Today that single origin is supplied by the Vite dev-server proxy, configured in
`spire-ui/vite.config.ts`. In a packaged run there is no Vite. So the UI image cannot be the
"static-serving image" §3 describes; it must be a reverse proxy, and the routing it performs is a
security control rather than a convenience.

Three consequences follow, all of them new work:

- **Four new required config keys** — the issuer URL and three client secrets. The three client *ids*
  default in code (`${SPIRE_OIDC_CLIENT_ID:spire-orchestrator}` and siblings) and are not required.
  Per §5.1's reasoning the three secrets must stay distinct; collapsing them would fail nothing
  visible and silently end the per-service isolation. **[review]** the first draft said seven keys,
  contradicting its own config contract.
- **TLS moves from advisory to required.** `SECURITY.md` states plainly that session cookies in
  plaintext are sniffable and replayable. The Ingress template ships TLS as its documented default,
  and both compose files carry the warning.
- **§5.1's rendered-manifest assertion extends.** It was specified to prove the two Tink keysets and
  two Postgres roles stay distinct. It must now also prove the three OIDC client secrets are distinct.

## Non-goals

- **No Terraform.** Unchanged from §4: no cloud target is chosen, and it would be the only artifact
  in the set that CI cannot verify without live credentials and real spend.
- **No native images, no Jib.** Unchanged from §3.
- **No new linter for `spire-ui`.** The project runs `tsc --noEmit`, `vitest run` and `vite build`.
  CI runs exactly those three. Adding eslint is a separate decision with its own baseline to clean:
  when adopting a pattern from a reference project, copy its structure, not a stricter quality gate
  the receiver never had to satisfy.
- **No CHANGELOG file.** Release notes are GitHub's auto-generated notes (see Decisions).
- **No sub-path deployment.** Every redirect target in the code is `/` (`GatewayAuthResource:36`, the
  logout post-logout path) and cookie paths are absolute, so the dashboard must sit at the origin
  root. The Ingress template offers no sub-path option and `NOTES.txt` says why. **[review]**
- **No fleet cost caps, no docs site.** Both are separate roadmap items.
- **Not a hosted offering.** FSL-1.1-ALv2 permits publishing images for self-hosting; it forbids
  reselling as a competing hosted service. Nothing in this work may describe the project as open
  source.

## Decisions taken in this pass

| Question | Decision | Why |
|---|---|---|
| First-pass scope | **Everything** — the workflows plus the full `deploy/` tree | Chosen by the operator over CI-only and CI+images |
| Where the single-origin proxy lives | **In the UI image** | Compose and Kubernetes then share one topology, so the login round-trip is verified locally before a cluster ever sees it. One Ingress rule. Costs one in-cluster hop per API call |
| Release trigger | **`v*` tag, auto-generated notes** | The commit discipline here already reads as release notes; a CHANGELOG file is one more thing that can go stale, and a forgotten section would fail releases |
| `simple` preset storage | **Bundled Postgres (PVC) + bundled single-node Redpanda** | Matches the existing `docker-compose.yml`; §7's lean, unchallenged |
| **Identity provider for `simple`** | **Bundled Keycloak + the shipped realm** | **[review]** Services refuse to boot without `SPIRE_OIDC_AUTH_SERVER_URL`, so a `simple` install without an IdP fails fast — not a one-command install. §6 already noted marauder ships one and we do not. `production` requires a BYO IdP and documents the realm contract |
| Service tests on the PR path | **Yes, as their own job** | §7's lean. The fast/slow seam already exists on module boundaries (below) |
| Compose filenames under `deploy/` | **`compose.yml` / `compose.ghcr.yml`** | §3 wrote `deploy/docker-compose.yml`, which collides by name with the repo-root dev-infra file. Compose's modern default name removes the ambiguity |
| Does `docker.yml` push? | **Yes — `:edge` on master** | **[review]** §2's marauder precedent is "build, don't push", but `e2e.yml` consumes GHCR images, so with releases as the only pusher the topology check could not run until after the release it exists to protect |

## The fast/slow test seam

§5.5 argues for splitting fast tests from Docker-backed ones but does not say where to cut. The cut
already exists, on module boundaries:

| Tier | Modules | Test sources | Needs Docker |
|---|---|---|---|
| Fast | `contract`, `diff`, `arch`, `encryption`, `http`, `llm`, `context-{jira,confluence,github,gitlab}`, `scm-{bitbucket,github,gitlab}` — 13 modules | 59 | no |
| Services | `gateway`, `orchestrator`, `review-worker` | 73 | yes — Quarkus Dev Services boots Postgres + Kafka per test JVM |

Counts are `.java` files under `src/test` on 2026-08-05, which includes WireMock resources and helpers,
not only test classes. They are descriptive, not a target. **[review]** the first draft said 58/68,
counted with a `*Test.java` glob that misses helpers and predates the D10 additions.

**The seam lives in Gradle, not in YAML.** Two lifecycle tasks in the root build:

```kotlin
tasks.register("testFast")      // the 13 Docker-free modules
tasks.register("testServices")  // gateway, orchestrator, review-worker
```

CI runs `./gradlew testFast` and `./gradlew testServices`. Two reasons this is not a list of module
paths in a workflow file: it stays runnable locally as a pre-commit loop, and it can be **guarded**.

The guard: a test asserting every subproject with a `test` task belongs to exactly one of the two
groups. Without it a seventeenth module escapes both groups, is tested by nothing, and CI stays green —
the same shape as the `ContractSchemaSnapshotTest` vacuity hole closed on 2026-08-02, where iterating
zero event types read as zero failures.

`spire-arch` needs no workflow of its own. Provider neutrality (ADR-020), the framework-free boundary,
the redirect-home check and the contract snapshot are all `test`-task failures already, so
`./gradlew build` enforces the architecture. Naming this here so no redundant job is added later.

## Images

Four images to `ghcr.io/artyomsv/`: `spire-gateway`, `spire-orchestrator`, `spire-review-worker`,
`spire-ui`.

### `Dockerfile` (repo root, context `.`)

Multi-stage, parameterised by `--build-arg SERVICE=gateway|orchestrator|review-worker`. One file for
three images, matching the in-repo precedent — `Dockerfile.dev` is already one image parameterised by
compose.

- **Build stage:** `eclipse-temurin:25-jdk`. Wrapper, `settings.gradle.kts`, `gradle.properties` and
  every module's `build.gradle.kts` copied **first** so the dependency layer caches independently of
  sources. Then sources, then `./gradlew :spire-${SERVICE}:build -x test`.
- **Normalise `gradlew` line endings** with the same `sed -i 's/\r$//'` `Dockerfile.dev:26` uses.
  CI on Linux never hits this, but `compose.yml` builds locally, and a Windows checkout produces
  `bad interpreter: /bin/sh^M`. **[review]**
- **Tests are excluded on purpose.** `ci.yml` has already run them; re-running inside a multi-arch
  buildx matrix runs them once per architecture under QEMU.
- **Runtime stage:** `eclipse-temurin:25-jre-alpine`, confirmed published for `linux/amd64` and
  `linux/arm64`.
- **`ENV QUARKUS_HTTP_PORT=8080`.** Each service's `application.yml` sets
  `quarkus.http.port: ${<SERVICE>_HTTP_PORT:3408x}`, so without this the image would `EXPOSE` and
  health-check a port nothing listens on, the container would report unhealthy forever, and compose's
  `depends_on: service_healthy` would never release. One env var covers all three services because it
  targets `quarkus.http.port` directly, and env beats an application.yaml default. **[review]** — the
  first draft asserted 8080 as if it were already true.
- **The fast-jar is copied as four layers**, `quarkus-app/lib/` first: `lib/` is hundreds of MB of
  unchanging dependencies, `app/` is ~1 MB of our classes. Copying `quarkus-app/` as one directory
  re-pushes everything on every code change, which on a JVM image (§5.4: ~10× a Go binary) is the
  difference between a seconds-long and a minutes-long push. Layout verified as `lib/`, `app/`,
  `quarkus/`, `quarkus-run.jar`.
- **Non-root:** explicit `USER 1001`.
- `EXPOSE 8080` — an internal container port. The 30000–49999 rule is host-side only.
- `HEALTHCHECK` against `/q/health/ready` on 8080.
- OCI labels including `org.opencontainers.image.licenses=FSL-1.1-ALv2` and the LICENSE file in the
  image (§5.7).
- A `.dockerignore` review: the current one is tuned for `Dockerfile.dev` and must keep `.env`,
  `.env.*`, `.handoff` and `*.dump` excluded for the production context too.

### `spire-ui/Dockerfile`

`node:22-alpine` builds (`npm ci && npm run build`), `nginx:1.27-alpine` serves. **The same OCI licence
label and LICENSE file** — `spire-ui` is FSL too. **[review]**

The nginx config is the load-bearing artifact of this entire pass:

```nginx
map $http_x_forwarded_proto $fwd_proto {     # never clobber an upstream terminator
    default $http_x_forwarded_proto;
    ''      $scheme;
}

location /webhooks { proxy_pass $GATEWAY; }      # SCM ingress — NOT the SPA
location /api      { proxy_pass $ORCHESTRATOR; } # + WebSocket upgrade
location /gw       { proxy_pass $GATEWAY; }      # + WebSocket upgrade
location /wk       { proxy_pass $WORKER; }
location /         { try_files $uri /index.html; }   # SPA routes

proxy_set_header Host $host;                     # NEVER rewrite
proxy_set_header X-Forwarded-Proto $fwd_proto;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

# on /api and /gw:
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;                        # nginx defaults to 60s
```

Upstreams come from three environment variables substituted at container start, so the same image
serves compose (service names) and Kubernetes (Service DNS) without a rebuild.

Four things here are not decoration:

- **`/webhooks` must route to the gateway.** It is a permit path in the gateway's own policy
  (`application.yml:53`) and it is how every review starts. Without this route an SCM delivery falls
  through to the SPA fallback and receives **`200` plus `index.html`** — so the SCM records a
  successful delivery, never retries, and every review event is lost silently. Dev cannot show this:
  the Cloudflare tunnel points straight at `gateway:39281`, bypassing the proxy entirely. **[review]**
  — this was the single worst defect in the first draft.
- **`Host` preservation** is a trap `vite.config.ts` already documents. Rewriting it makes the OIDC
  `redirect_uri` come back pointing at the backend's own port, so the login round-trip lands on the
  wrong origin and fails.
- **`X-Forwarded-Proto` must pass through, not be overwritten.** In the Kubernetes topology this spec
  mandates, the Ingress terminates TLS and forwards over http, so nginx's `$scheme` is `http`. Setting
  the header from `$scheme` would overwrite the controller's `https`, and Quarkus — trusting forwarded
  headers — would mint an `http://` redirect_uri and drop the cookie's `Secure` attribute. This breaks
  **only** behind a TLS Ingress, so the compose e2e would pass clean: exactly the
  verified-locally-broken-in-cluster failure this spec exists to prevent. **[review]**
- **WebSocket mechanics are explicit.** `proxy_http_version 1.1` plus the `Upgrade`/`Connection`
  headers, and a raised `proxy_read_timeout` — nginx's 60s default would cut idle timeline and
  attention sockets, producing reconnect churn the dashboard surfaces as an outage. **[review]**

### Code changes this requires

Three prod-profile properties, in all three services:

| Property | Why |
|---|---|
| `quarkus.http.proxy.proxy-address-forwarding=true` | Otherwise the services honour the proxy's address, not the client's. Absent-and-correct until now because Vite forwards no `X-Forwarded-*` |
| `quarkus.http.proxy.trusted-proxies=<UI network CIDR>` | **[review]** `D10-AUTH-PLAN.md:338` calls the property above "a header-spoofing vector while service ports stay directly reachable". Unpaired, anything that can reach a service port forges `X-Forwarded-For`/`-Proto`. No deploy artifact may publish a service port directly, and `render.sh` asserts that |
| `quarkus.oidc.authentication.cookie-force-secure=true` | Belt-and-braces behind a TLS terminator that forwards over http **[review]** |

This is application config, not packaging, and it belongs in this work rather than being discovered
during the kind smoke test.

## The `deploy/` tree

```
deploy/
  compose.yml                   infra + IdP + 4 services, built locally
  compose.ghcr.yml              infra + IdP + 4 services from GHCR   <- the one-command install
  .env.example                  the PACKAGED config contract
  keycloak/realm-spire.json     the shipped realm (copied from infra/, pinned issuer)
  helm/spire/
    Chart.yaml
    values.yaml  values-simple.yaml  values-production.yaml
    templates/                  4 Deployments, 4 Services, Ingress, ConfigMap,
                                Secret references, gateway-role Job, NOTES.txt
    tests/render.sh             the invariant assertions, and --self-test
  kustomize/base/               inflates the chart: helmCharts: + --enable-helm
  kustomize/overlays/simple/
  kustomize/overlays/production/
  k8s/simple/spire.yaml         rendered plain YAML, drift-checked
  k8s/production/spire.yaml
  render-manifests.sh           the generator; --check fails CI on drift
```

The derivation chain is §2's, copied deliberately: the chart is the single source of truth, kustomize
inflates it, plain YAML is rendered from it, and `render-manifests.sh --check` turns divergence from a
discipline problem into a build failure. Helm users, kustomize users and `kubectl apply` users receive
the same manifests.

The repo-root `docker-compose.yml`, `docker-compose.dev.yml`, `docker-compose.idp.yml` and
`docker-compose.auth.yml` are untouched. They are the development stack; `deploy/` is the deployment
one.

### Presets

| Preset | Postgres | Kafka | Identity provider | For |
|---|---|---|---|---|
| `simple` | bundled, PVC | bundled single-node Redpanda | **bundled Keycloak**, realm auto-imported | self-host, homelab, evaluation |
| `production` | external, or CloudNativePG | external, or Strimzi | **bring your own**, realm contract documented | a real cluster |

The bundled Keycloak needs the pinned-hostname treatment ROADMAP decision 5 records: a fixed
`KC_HOSTNAME` with `KC_HOSTNAME_BACKCHANNEL_DYNAMIC` so front- and backchannel can differ while the
issuer stays constant. An unpinned instance derives its issuer from the Host it was reached by, which
makes the browser's issuer and the services' issuer disagree and every token fail validation.

## Config contract

`deploy/.env.example` is its own file, not the repo-root one. The dev contract carries `KEYCLOAK_*`
admin credentials, JDWP ports and the `SPIRE_SCM_STUB` / `SPIRE_LLM_PROVIDER=stub` toggles, none of
which belong in a deployment.

### Operator-supplied, required, no defaults

```
POSTGRES_PASSWORD                GATEWAY_POSTGRES_PASSWORD
SPIRE_ENCRYPTION_KEYSET          SPIRE_ENCRYPTION_WEBHOOK_KEYSET
SPIRE_OIDC_AUTH_SERVER_URL
SPIRE_OIDC_CLIENT_SECRET  x 3    (orchestrator / gateway / worker — distinct)
```

### Deploy-artifact wiring, per service — **[review]**

The first draft's list booted nothing. All three services define
`quarkus.datasource.jdbc.url`, `username` and `password` **only under `%dev`**, pointing at
`localhost:34432`; the base (prod) profile carries `db-kind` and `currentSchema` and nothing else.
`kafka.bootstrap.servers` is likewise `%dev`-only, so in prod the SmallRye connector falls back to
`localhost:9092` — silently wrong inside a container. A deployment supplying exactly the list above
gets three services that cannot start (Flyway `migrate-at-start` with no datasource) and, if they
could, would connect to no broker.

So every deploy artifact injects, per service:

| Variable | orchestrator / worker | gateway |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | the shared database | same database |
| `QUARKUS_DATASOURCE_USERNAME` | `POSTGRES_USER` | `GATEWAY_POSTGRES_USER` — the schema-scoped role |
| `QUARKUS_DATASOURCE_PASSWORD` | `POSTGRES_PASSWORD` | `GATEWAY_POSTGRES_PASSWORD` |
| `KAFKA_BOOTSTRAP_SERVERS` | the broker | the broker |

That gateway row is precisely what assertion 3 greps for, so the first draft implicitly assumed this
wiring while never naming it as work. For the `production` preset these are operator-facing values in
`values.yaml`, not internal plumbing.

### The chart must never generate a secret

Helm's ordinary idiom is `randAlphaNum` behind `if not .Values.existingSecret`. Here that is data loss
wearing a best practice's clothes.

`SPIRE_ENCRYPTION_KEYSET` **decrypts rows at rest** — event payloads, provider secrets, assembled
context. A chart that mints one rotates it on the next `helm upgrade`, and every encrypted row becomes
permanently unreadable. The same applies to the gateway's dedicated webhook keyset.

So: `existingSecret` references only, no generation, no defaults, and a `required` template call that
fails the render naming the missing key. `values-production.yaml` must not be able to point both
keysets at the same Secret.

The generalisation worth keeping: Helm secret generation is safe for credentials that are **shared
state** (a database password both sides read from one Secret) and unsafe for credentials that are
**keys to existing data**.

## The invariant assertions — `tests/render.sh`

Assertions over properties that exist **only as configuration shape**, which is why no unit test can
reach them — §5.1's "flattening them would fail nothing and break everything".

**The assertions span two sources**, and the script says which for each: rendered Helm output, and
in-repo config files that are baked into images rather than templated. **[review]** — the first draft
called them all "over rendered manifests", which would have made two of them vacuous.

Over **rendered manifests**:

1. `SPIRE_ENCRYPTION_KEYSET` present on orchestrator and worker, **absent** on gateway
2. `SPIRE_ENCRYPTION_WEBHOOK_KEYSET` present on gateway, **absent** on orchestrator and worker
3. The gateway's `QUARKUS_DATASOURCE_USERNAME` differs from the other two services'
4. The three OIDC client secrets resolve to three **distinct** Secret keys
5. No Service or Ingress publishes a service port directly — the UI is the only ingress path
   (the `trusted-proxies` pairing depends on this)

Over **in-repo config**:

6. The nginx template carries `/webhooks`, `/api`, `/gw`, `/wk` and the SPA fallback, in that
   precedence order; has WebSocket upgrade on `/api` and `/gw`; does not rewrite `Host`; and does
   **not** contain the literal `X-Forwarded-Proto $scheme`
7. The three `application.yml` cookie paths (`/api`, `/gw`, `/wk`) are present and distinct

### `--self-test` is not optional

`tests/render.sh --self-test` renders deliberately-broken values and asserts **each check catches its
own break**.

Assertions 1, 2, 5 and the negative clauses of 6 are *negative*: the value must be absent where it does
not belong. Negative assertions rot silently, because a renamed key makes them pass trivially. For a
"never present" check the self-test is the only thing distinguishing "correctly absent" from "looking
in the wrong place". Passing greps would otherwise be indistinguishable from greps that found nothing.

This mirrors the mutation-verification discipline already used for every guard in this repo: break the
production line, confirm exactly one check fails.

## Workflows

Nine workflows plus `.github/dependabot.yml`, which is a config file rather than a workflow. **[review]**

| # | File | Trigger | Contents |
|---|---|---|---|
| 1 | `ci.yml` | PR + push to **master** | Job `fast`: `./gradlew testFast`, target under 3 min. Job `ui`: `npm ci`, `tsc --noEmit`, `vitest run`, `vite build`. Job `services`: `./gradlew testServices assemble`. All Java jobs set JDK 25 via `actions/setup-java` |
| 2 | `codeql.yml` | PR + weekly | Java (manual build mode) and TypeScript |
| 3 | `secret-scan.yml` | PR + push | gitleaks. `.gitleaksignore` covers the three `dev-only-*-secret` values in the realm JSON and the `.env.example` placeholders |
| 4 | `semgrep.yml` | PR | **Blocking.** The repo is already Semgrep-clean, so this records existing discipline rather than raising the bar |
| 5 | `docker.yml` | push to master | Build all four images, Trivy scan each, SARIF to the Security tab, `exit-code: 0`, **push `:edge` and `:sha-<short>`** |
| 6 | `release.yml` | `v*` tag | Multi-arch buildx (amd64 + arm64) to GHCR, **the e2e script against the amd64 image before pushing**, cosign keyless signing via OIDC, CycloneDX SBOM, build provenance, GitHub Release with auto-generated notes, chart `appVersion` from the tag |
| 7 | `manifests.yml` | `deploy/**` | `helm lint` on both presets, `tests/render.sh` and `--self-test`, `render-manifests.sh --check`, `kubeconform` (CRDs-catalog for third-party schemas), `kustomize build` of base and both overlays, and a kind install smoke test |
| 8 | `e2e.yml` | nightly + manual | `compose.ghcr.yml` against **`:edge`**, then the round-trip below |
| 9 | `dependabot-auto-merge.yml` | on Dependabot PRs | Scoped to github-actions and patch-level npm only |
| — | `dependabot.yml` | weekly | gradle, npm, github-actions, docker |

**Triggers say `master`, not `main`.** The default branch here is `master`; every trigger copied from a
reference project will say `main` and silently never fire, which presents as an empty Actions tab
rather than as an error.

**Why `docker.yml` pushes.** §2's marauder precedent is build-without-pushing, and the first draft
copied it — but `e2e.yml` consumes GHCR images while `release.yml` was the only pusher, so the topology
check could not run until after the release it exists to protect, and nightly e2e would have exercised
the last release rather than current master. An `:edge` tag fixes both, and it is also the tag someone
tracking the tip would want. **[review]**

**Why `ci.yml` job `services` also runs `assemble`:** §5.6's trap is that Gradle's `test` task uses the
toolchain while Quarkus *packaging* runs on the daemon's JVM, so with an older `JAVA_HOME` every test
passes and packaging then fails with `UnsupportedClassVersionError ... class file version 69.0`. Only
packaging exercises that path. `assemble` rather than `build` because `build` would re-run the fast
tier job 1 already covered, and `assemble` is confirmed to trigger `quarkusBuild`.

**Why Trivy is report-only:** §2's recorded reasoning. The residual HIGH/CRITICAL findings on a fully
patched image are language-stdlib CVEs fixed only in unreleased toolchain versions — an advisory
treadmill no bump can clear. Fixable module and OS CVEs are bumped promptly and triaged from the
Security tab.

**Why release builds stay off the PR path:** §5.4. JVM images are large and `linux/arm64` via QEMU is
slow; expect tens of minutes.

GHCR packages default to private on first push even from a public repository, so publishing needs one
manual visibility flip the first time.

## `e2e.yml` — the things dev has never proven

Every other check in this set is a variation on something already verified locally. These are not.

```
POST /webhooks/{provider}/{key}    -> 202      the proxy does not swallow SCM ingress
GET  /                             -> 200      static assets served
GET  /api/reviews    (no session)  -> 302 IdP  the policy reaches through nginx
GET  /api/providers  (viewer)      -> 403      @RolesAllowed survives the proxy
GET  /api/reviews    (viewer)      -> 200
WS   /api/ws/timeline              -> 101      upgrade traverses nginx
GET  /gw/auth/login                -> 303 /    the per-prefix session
GET  /wk/auth/login                -> 303 /
psql as gateway role, SELECT from an orchestrator-schema table -> permission denied
```

The first line is the corrected defect: a signed delivery must reach the gateway, not the SPA
fallback. **[review]**

The `/gw` and `/wk` lines are the specific failure that produced *"failed to fetch"* on the Webhooks
page and a lone failing Context card on an otherwise working review — invisible in dev, because Vite
happened to make it work.

The last line is the only check that tests the property `SECURITY.md` actually cares about. **[review]**
Assertion 3 proves the rendered *usernames* differ and the provisioning Job proves the role *exists*;
neither proves the gateway role **cannot read** the orchestrator schema. An operator who fixes a
permission error with a broad `GRANT`, or grants the gateway role membership in the main role, collapses
the boundary with every piece of configuration still distinct. Only a live privilege probe discriminates.

Tokens come from a password grant against the shipped realm's obviously-synthetic users. No real
credential enters CI.

## Build order

Ordered by risk, so each step's unknowns can fail alone.

1. **`ci.yml`** — nothing else is safe to iterate on without it. Includes the two Gradle lifecycle
   tasks and their coverage guard.
2. **Production Dockerfiles + nginx config + the three prod-profile properties**, then `docker.yml`
   publishing `:edge`.
3. **`deploy/compose.ghcr.yml` + `e2e.yml`** — proves the topology before any chart encodes it. Now
   actually possible, because step 2 publishes `:edge`.
4. **Helm chart + the gateway-role Job + `tests/render.sh` + `--self-test`.**
5. **kustomize + rendered `k8s/` + `render-manifests.sh --check`**, then `manifests.yml`.
6. **`release.yml`** — last, because it publishes. Everything else must be green first.
7. **`codeql.yml`, `secret-scan.yml`, `semgrep.yml`, `dependabot.yml`, auto-merge** — cheap and
   independent; land anywhere.

Step 3 precedes step 4 deliberately. A chart's job is to encode a topology; encoding an unverified one
means debugging Helm templating and nginx routing at the same time.

## Verification

How we know each piece works, rather than looks done:

| Artifact | Check |
|---|---|
| The two Gradle test groups | The coverage guard fails when a module belongs to neither |
| Service images | `e2e.yml` runs the real auth round-trip and a webhook delivery against them |
| UI image / nginx routing | Same, plus assertion 6 on the in-repo template |
| The gateway's privilege boundary | The live `permission denied` probe in `e2e.yml` |
| Helm chart | `helm lint`, `tests/render.sh`, and `--self-test` proving each assertion discriminates |
| kustomize + rendered YAML | `render-manifests.sh --check` fails on any drift; `kubeconform` validates schemas; kind install proves it applies |
| Release pipeline | Verified by cutting a real tag once everything else is green |

## Documentation this pass must produce

- A prominent **"do not expose this without TLS"** warning in `NOTES.txt`, `README.md` and both
  compose files (§6, still applicable — D10 stops casual access, not an on-path attacker).
- `deploy/README.md`: the one-command install, the two presets, the realm contract for a BYO IdP, how
  to supply the four required secrets, and the manual `CREATE ROLE` path for operators who will not
  hand a chart superuser credentials.
- `docs/CICD-AND-PACKAGING.md` updated — §1's "starting point" table and §7's open decisions are
  answered by this spec; §6's parking note becomes a record of why the order was what it was.
- `docs/ROADMAP.md`: the CI/CD row moves out of "what is actually left".

## Risks and open items

- **arm64 release times.** If multi-arch under QEMU proves unworkable, the fallback is amd64-only
  releases with arm64 added later on native runners. Decide with a real measurement, not a guess.
- **The gateway-role provisioning Job needs superuser credentials**, which the chart must be handed.
  For the `production` preset against an external Postgres that is a real ask some operators will
  refuse, so `deploy/README.md` documents the two statements to run by hand — the same pair
  `.env.example` already carries for compose. **[review]**
- **First-boot ordering is a Helm hook problem, not a Flyway one.** **[review]** The first draft
  worried about a cross-service Flyway race; there is none, because the three services own three
  schemas with three independent `flyway_schema_history` tables. The real constraint is that the
  gateway cannot connect at all until its scoped role exists, so the provisioning Job must complete
  before the gateway Deployment starts.
- **Version number for the first FSL release.** `v0.1.0-apache` marks the licence boundary and the
  Gradle version is `0.1.0-SNAPSHOT`. §6 said tagging a `v1` should be gated on D10, which is now
  clear. The number is not a CI decision and can be settled when a tag is actually cut.
- **`trusted-proxies` needs a concrete CIDR per topology.** In compose it is the bridge network; in
  Kubernetes the pod CIDR, which is cluster-specific and therefore a `values.yaml` field with no safe
  default. Assertion 5 keeps the surrounding assumption honest by proving nothing else is exposed.
