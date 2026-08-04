# CI/CD and packaging — design

**Date:** 2026-08-05
**Status:** approved, not yet implemented
**Supersedes the "open decisions" table in** [`docs/CICD-AND-PACKAGING.md`](../../CICD-AND-PACKAGING.md) **§7.**

## Goal

Give this repository the two things it has never had: **verification that runs somewhere other than
one workstation**, and **artifacts somebody else can run**. Concretely — ten GitHub Actions
workflows, four production images published to GHCR, and a `deploy/` tree covering Docker Compose,
Helm and kustomize from a single source of truth.

The analysis behind most of this already exists in `docs/CICD-AND-PACKAGING.md` and is not repeated.
That document was written on 2026-08-03, one day before D10 (operator authentication) landed. This
spec records what was decided on top of it, and — more importantly — **what D10 added to the job**
that the analysis predates.

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

- **Seven new required config keys** — the issuer URL, three client ids, three client secrets. Per
  §5.1's own reasoning the three secrets must stay distinct; collapsing them would fail nothing
  visible and silently end the per-service isolation.
- **TLS moves from advisory to required.** `SECURITY.md` states plainly that session cookies in
  plaintext are sniffable and replayable. The Ingress template ships TLS as its documented default,
  and both compose files carry the warning.
- **§5.1's rendered-manifest assertion extends.** It was specified to prove the two Tink keysets and
  two Postgres roles stay distinct. It must now also prove the three OIDC client secrets are distinct
  and the three cookie paths are present.

## Non-goals

- **No Terraform.** Unchanged from §4: no cloud target is chosen, and it would be the only artifact
  in the set that CI cannot verify without live credentials and real spend.
- **No native images, no Jib.** Unchanged from §3.
- **No new linter for `spire-ui`.** The project runs `tsc --noEmit`, `vitest run` and `vite build`.
  CI runs exactly those three. Adding eslint is a separate decision with its own baseline to clean,
  and bundling it into CI onboarding is the discipline-mismatch failure that
  `~/.claude/rules/reference-pattern-adoption.md` describes.
- **No CHANGELOG file.** Release notes are GitHub's auto-generated notes (see Decisions).
- **No fleet cost caps, no docs site.** Both are separate roadmap items.
- **Not a hosted offering.** FSL-1.1-ALv2 permits publishing images for self-hosting; it forbids
  reselling as a competing hosted service. Nothing in this work may describe the project as open
  source.

## Decisions taken in this pass

| Question | Decision | Why |
|---|---|---|
| First-pass scope | **Everything** — ten workflows plus the full `deploy/` tree | Chosen by the operator over CI-only and CI+images |
| Where the single-origin proxy lives | **In the UI image** | Compose and Kubernetes then share one topology, so the login round-trip is verified locally before a cluster ever sees it. One Ingress rule. Costs one in-cluster hop per API call |
| Release trigger | **`v*` tag, auto-generated notes** | The commit discipline here already reads as release notes; a CHANGELOG file is one more thing that can go stale, and a forgotten section would fail releases |
| `simple` preset storage | **Bundled Postgres (PVC) + bundled single-node Redpanda** | Matches the existing `docker-compose.yml`; §7's lean, unchallenged |
| Service tests on the PR path | **Yes, as their own job** | §7's lean. The fast/slow seam already exists on module boundaries (below) |
| Compose filenames under `deploy/` | **`compose.yml` / `compose.ghcr.yml`** | §3 wrote `deploy/docker-compose.yml`, which collides by name with the repo-root dev-infra file. Compose's modern default name removes the ambiguity |

## The fast/slow test seam

§5.5 argues for splitting fast tests from Docker-backed ones but does not say where to cut. The cut
already exists, on module boundaries:

| Tier | Modules | Test files | Needs Docker |
|---|---|---|---|
| Fast | `contract`, `diff`, `arch`, `encryption`, `http`, `llm`, `context-{jira,confluence,github,gitlab}`, `scm-{bitbucket,github,gitlab}` — 13 modules | 58 | no |
| Services | `gateway`, `orchestrator`, `review-worker` | 68 | yes — Quarkus Dev Services boots Postgres + Kafka per test JVM |

**The seam lives in Gradle, not in YAML.** Two lifecycle tasks in the root build:

```kotlin
tasks.register("testFast")      // the 13 Docker-free modules
tasks.register("testServices")  // gateway, orchestrator, review-worker
```

CI runs `./gradlew testFast` and `./gradlew testServices`. Two reasons this is not a list of module
paths in a workflow file: it stays runnable locally as a pre-commit loop, and it can be **guarded**.

The guard: a test asserting every subproject with a `test` task belongs to exactly one of the two
groups. Without it a seventeenth module escapes both groups, is tested by nothing, and CI stays green
— the same shape as the `ContractSchemaSnapshotTest` vacuity hole closed on 2026-08-02, where
iterating zero event types read as zero failures.

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
- **Tests are excluded on purpose.** `ci.yml` has already run them; re-running inside a multi-arch
  buildx matrix runs them once per architecture under QEMU.
- **Runtime stage:** `eclipse-temurin:25-jre-alpine`. If a musl incompatibility surfaces, fall back to
  `eclipse-temurin:25-jre` and record why.
- **The fast-jar is copied as four layers**, `quarkus-app/lib/` first: `lib/` is hundreds of MB of
  unchanging dependencies, `app/` is ~1 MB of our classes. Copying `quarkus-app/` as one directory
  re-pushes everything on every code change, which on a JVM image (§5.4: ~10× a Go binary) is the
  difference between a seconds-long and a minutes-long push.
- **Non-root:** explicit `USER 1001`.
- `EXPOSE 8080` — an internal container port. The 30000–49999 rule is host-side only.
- `HEALTHCHECK` against `/q/health/ready`.
- OCI labels including `org.opencontainers.image.licenses=FSL-1.1-ALv2` and the LICENSE file in the
  image (§5.7).
- A `.dockerignore` review: the current one is tuned for `Dockerfile.dev` and must keep `.env`,
  `.env.*`, `.handoff` and `*.dump` excluded for the production context too.

### `spire-ui/Dockerfile`

`node:22-alpine` builds (`npm ci && npm run build`), `nginx:1.27-alpine` serves. The nginx config is
the load-bearing artifact of this entire pass:

```
location /api  ->  orchestrator     + WebSocket upgrade
location /gw   ->  gateway          + WebSocket upgrade
location /wk   ->  worker
location /     ->  try_files $uri /index.html      (SPA routes)

proxy_set_header Host $host;                 # NEVER rewrite
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

Upstreams come from three environment variables substituted at container start, so the same image
serves compose (service names) and Kubernetes (Service DNS) without a rebuild.

**`Host` preservation is a trap `vite.config.ts` already documents.** Rewriting it makes the OIDC
`redirect_uri` come back pointing at the backend's own port, so the login round-trip lands on the
wrong origin and fails.

### One code change this requires

The three services need `quarkus.http.proxy.proxy-address-forwarding=true` in the **prod profile**.
Without it they honour the proxy's address rather than the client's. Nothing in dev needed this
because Vite forwards no `X-Forwarded-*` headers, so the property has been absent-and-correct until
the moment a real proxy appears in front. This is application config, not packaging, and it belongs
in this work rather than being discovered during the kind smoke test.

## The `deploy/` tree

```
deploy/
  compose.yml                   infra + 4 services, built locally
  compose.ghcr.yml              infra + 4 services from GHCR   <- the one-command install
  .env.example                  the PACKAGED config contract
  helm/spire/
    Chart.yaml
    values.yaml  values-simple.yaml  values-production.yaml
    templates/                  4 Deployments, 4 Services, Ingress, ConfigMap,
                                Secret references, NOTES.txt
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

| Preset | Postgres | Kafka | For |
|---|---|---|---|
| `simple` | bundled, PVC | bundled single-node Redpanda | self-host, homelab, evaluation |
| `production` | external, or CloudNativePG | external, or Strimzi | a real cluster |

## Config contract

`deploy/.env.example` is its own file, not the repo-root one. The dev contract carries `KEYCLOAK_*`
admin credentials, JDWP ports and the `SPIRE_SCM_STUB` / `SPIRE_LLM_PROVIDER=stub` toggles, none of
which belong in a deployment.

Required, no defaults:

```
POSTGRES_PASSWORD                GATEWAY_POSTGRES_PASSWORD
SPIRE_ENCRYPTION_KEYSET          SPIRE_ENCRYPTION_WEBHOOK_KEYSET
SPIRE_OIDC_AUTH_SERVER_URL
SPIRE_OIDC_CLIENT_SECRET  x 3    (orchestrator / gateway / worker — distinct)
```

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

Six assertions over rendered manifests. Each proves a property that exists **only as configuration
shape**, which is why no unit test can reach it — §5.1's "flattening them would fail nothing and break
everything".

1. `SPIRE_ENCRYPTION_KEYSET` present on orchestrator and worker, **absent** on gateway
2. `SPIRE_ENCRYPTION_WEBHOOK_KEYSET` present on gateway, **absent** on orchestrator and worker
3. The gateway's datasource user differs from the other two services'
4. The three OIDC client secrets resolve to three **distinct** Secret keys
5. The nginx config carries all three prefixes, WebSocket upgrade on `/api` and `/gw`, and does not
   rewrite `Host`
6. Cookie paths `/api`, `/gw`, `/wk` are present and distinct across the three services

### `--self-test` is not optional

`tests/render.sh --self-test` renders deliberately-broken values and asserts **each check catches its
own break**.

Assertions 1–3 are *negative*: the value must be absent where it does not belong. Negative assertions
rot silently, because a renamed key makes them pass trivially. For a "never present" check the
self-test is the only thing distinguishing "correctly absent" from "looking in the wrong place". Six
passing greps would otherwise be indistinguishable from six greps that found nothing.

This mirrors the mutation-verification discipline already used for every guard in this repo: break the
production line, confirm exactly one check fails.

## Workflows

| # | File | Trigger | Contents |
|---|---|---|---|
| 1 | `ci.yml` | PR + push to master | Job `fast`: `./gradlew testFast`, target under 3 min. Job `ui`: `npm ci`, `tsc --noEmit`, `vitest run`, `vite build`. Job `services`: `./gradlew testServices assemble` — `assemble` triggers `quarkusBuild`, exercising packaging without re-running the fast tier. All Java jobs set JDK 25 via `actions/setup-java` |
| 2 | `codeql.yml` | PR + weekly | Java (manual build mode) and TypeScript |
| 3 | `secret-scan.yml` | PR + push | gitleaks. `.gitleaksignore` covers the three `dev-only-*-secret` values in `infra/keycloak/realm-spire.json` and the `.env.example` placeholders |
| 4 | `semgrep.yml` | PR | **Blocking.** The repo is already Semgrep-clean, so this records existing discipline rather than raising the bar |
| 5 | `docker.yml` | push to master | Build all four images (amd64 only), Trivy scan each, SARIF to the Security tab, `exit-code: 0`. **Does not push** |
| 6 | `release.yml` | `v*` tag | Multi-arch buildx (amd64 + arm64) to GHCR, cosign keyless signing via OIDC, CycloneDX SBOM, build provenance, GitHub Release with auto-generated notes, chart `appVersion` set from the tag |
| 7 | `manifests.yml` | `deploy/**` | `helm lint` on both presets, `tests/render.sh`, `render-manifests.sh --check`, `kubeconform` (CRDs-catalog for third-party schemas), `kustomize build` of base and both overlays, and a kind install smoke test |
| 8 | `e2e.yml` | nightly + manual | Compose up from GHCR images plus a Keycloak with the dev realm, then the auth round-trip below |
| 9 | `dependabot.yml` | weekly | gradle, npm, github-actions, docker |
| 10 | `dependabot-auto-merge.yml` | on #9's PRs | Scoped to github-actions and patch-level npm only |

**Why `ci.yml` job `services` also runs `assemble`:** §5.6's trap is that Gradle's `test` task uses the
toolchain while Quarkus *packaging* runs on the daemon's JVM, so with an older `JAVA_HOME` every test
passes and packaging then fails with `UnsupportedClassVersionError ... class file version 69.0`. Only
packaging exercises that path. `assemble` rather than `build` because `build` would re-run the fast
tier that job 1 already covered. Observed locally on 2026-08-03.

**Why Trivy is report-only:** §2's recorded reasoning. The residual HIGH/CRITICAL findings on a fully
patched image are language-stdlib CVEs fixed only in unreleased toolchain versions — an advisory
treadmill no bump can clear. Fixable module and OS CVEs are bumped promptly and triaged from the
Security tab. The same reasoning applies to a JVM base image.

**Why release builds stay off the PR path:** §5.4. JVM images are large and `linux/arm64` via QEMU is
slow; expect tens of minutes.

## `e2e.yml` — the one thing dev has never proven

Every other check in this set is a variation on something already verified locally. The auth round
trip through a real proxy is not.

```
GET /                              -> 200        static assets served
GET /api/reviews    (no session)   -> 302 IdP    the policy reaches through nginx
GET /api/providers  (viewer token) -> 403        @RolesAllowed survives the proxy
GET /api/reviews    (viewer token) -> 200
WS  /api/ws/timeline               -> 101        upgrade traverses nginx
GET /gw/auth/login                 -> 303 /      the per-prefix session
GET /wk/auth/login                 -> 303 /
```

The last two lines are the specific failure that produced *"failed to fetch"* on the Webhooks page and
a lone failing Context card on an otherwise working review — invisible in dev, because Vite happened
to make it work.

Tokens come from a password grant against the dev realm's obviously-synthetic users. No real
credential enters CI.

## Build order

Ordered by risk, so each step's unknowns can fail alone.

1. **`ci.yml`** — nothing else is safe to iterate on without it. Includes the two Gradle lifecycle
   tasks and their coverage guard.
2. **Production Dockerfiles + nginx config + `proxy-address-forwarding`**, then `docker.yml`.
3. **`deploy/compose.ghcr.yml` + `e2e.yml`** — proves the topology before any chart encodes it.
4. **Helm chart + `tests/render.sh` + `--self-test`.**
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
| Service images | `e2e.yml` runs the real auth round-trip against them |
| UI image / nginx routing | Same, plus `tests/render.sh` assertion 5 on the rendered config |
| Helm chart | `helm lint`, `tests/render.sh`, and `--self-test` proving each assertion discriminates |
| kustomize + rendered YAML | `render-manifests.sh --check` fails on any drift; `kubeconform` validates schemas; kind install proves it applies |
| Release pipeline | Verified by cutting a real tag once everything else is green |

## Documentation this pass must produce

- A prominent **"do not expose this without TLS"** warning in `NOTES.txt`, `README.md` and both
  compose files (§6, still applicable — D10 stops casual access, not an on-path attacker).
- `deploy/README.md`: the one-command install, the two presets, and how to supply the four
  encryption/OIDC secrets.
- `docs/CICD-AND-PACKAGING.md` updated — §1's "starting point" table and §7's open decisions are
  answered by this spec; §6's parking note becomes a record of why the order was what it was.
- `docs/ROADMAP.md`: the CI/CD row moves out of "what is actually left".

## Risks and open items

- **arm64 release times.** If multi-arch under QEMU proves unworkable, the fallback is amd64-only
  releases with arm64 added later on native runners. Decide with a real measurement, not a guess.
- **First-boot Flyway race** (§5.3). Three services own three schemas and all migrate at boot.
  Quarkus runs Flyway itself so this is mostly self-managing, but the first deploy of the `simple`
  preset against an empty database is where it would show. Watch it during the kind smoke test.
- **The gateway's scoped Postgres role** is provisioned in compose by
  `infra/postgres-init/01-gateway-role.sh`, which only runs on a fresh volume. The chart needs an
  equivalent that is idempotent — a Job or an init container — because a Helm install onto an existing
  database must not silently skip it. Assertion 3 proves the *config* is distinct, not that the role
  exists; both are needed.
- **Version number for the first FSL release.** `v0.1.0-apache` marks the licence boundary and the
  Gradle version is `0.1.0-SNAPSHOT`. §6 said tagging a `v1` should be gated on D10, which is now
  clear. The number is not a CI decision and can be settled when a tag is actually cut.
- **`e2e.yml` needs a Keycloak in CI.** The bundled `docker-compose.idp.yml` shape works, but the
  issuer-hostname trap documented in ROADMAP decision 5 applies: a pinned `KC_HOSTNAME` lets front and
  backchannel differ while the issuer stays fixed. Get this right once, in the e2e compose file.
