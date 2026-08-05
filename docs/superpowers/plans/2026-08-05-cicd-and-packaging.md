# CI/CD and Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Code Spire continuous verification on GitHub Actions and four publishable production images with a `deploy/` tree covering Compose, Helm and kustomize from one source of truth.

**Architecture:** Nine workflows plus a Dependabot config. One parameterised multi-stage `Dockerfile` builds the three Quarkus services; `spire-ui/Dockerfile` builds an nginx image that serves the SPA **and reverse-proxies `/webhooks`, `/api`, `/gw`, `/wk`** — that proxy is what puts all four services on one origin, which is the mechanism ADR-022's cookie-path scoping depends on. The Helm chart is the single source of truth; kustomize inflates it and plain YAML is rendered from it, with a drift check in CI.

**Tech Stack:** GitHub Actions, Docker buildx, GHCR, Trivy, cosign, Syft/CycloneDX, Helm 3, kustomize, kubeconform, kind, nginx 1.27, Gradle 9 / Java 25, Quarkus 3.37.1.

**Spec:** `docs/superpowers/specs/2026-08-05-cicd-and-packaging-design.md` — read it before starting. Section references below (§n) are to `docs/CICD-AND-PACKAGING.md`.

## Global Constraints

- **Default branch is `master`.** Every workflow trigger says `master`. A trigger saying `main` never fires and presents as an empty Actions tab, not an error.
- **JDK 25** everywhere (`actions/setup-java` with `distribution: temurin`, `java-version: 25`). Gradle's `test` task uses the toolchain but Quarkus *packaging* uses the daemon JVM; an older `JAVA_HOME` gives passing tests and `UnsupportedClassVersionError ... class file version 69.0` at packaging.
- **Host-exposed ports must be in 30000–49999.** Container-internal ports stay conventional (8080 inside an image is correct).
- **Java 4-space indent; YAML/TS 2-space.** Explicit types over `var` in Java. `interface` over `type` for TS object shapes.
- **Commit messages: imperative mood, ≤72 chars on the first line, body for anything non-trivial.** Never mention AI/agentic authoring, model names, vendor names, "generated with", or subagents/review rounds. Describe what changed and why.
- **Never call the project "open source."** It is **source-available**, licensed per module (ADR-021). The four deployables are **FSL-1.1-ALv2**; every image carries `org.opencontainers.image.licenses=FSL-1.1-ALv2` and the LICENSE file.
- **No fabricated data.** Any test value must be obviously synthetic: `example.invalid`, `TEST-`/`CANARY-` prefixes, `dev-only-*`.
- **No secrets with defaults.** A missing required secret fails fast, naming the variable.
- **Icons in UI work: lucide-react only, never emoji.** (Applies only if a task touches `spire-ui/src`; none here do.)
- **Commit after every task.** Do not push unless explicitly asked.

## File Structure

| Path | Responsibility |
|---|---|
| `build.gradle.kts` (root, modify) | Declares the two test-tier module lists and the `testFast` / `testServices` lifecycle tasks |
| `spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java` | Guard: every included module with tests belongs to exactly one tier |
| `Dockerfile` | Production image for the three Quarkus services, `--build-arg SERVICE` |
| `.dockerignore` (modify) | Extended for the production build context |
| `spire-ui/Dockerfile` | Node build → nginx runtime |
| `spire-ui/nginx/default.conf.template` | **The single-origin proxy.** The security-critical file in this plan |
| `spire-*/src/main/resources/application.yml` (modify ×3) | Three prod-profile proxy/cookie properties |
| `deploy/compose.yml` | Locally-built stack: infra + IdP + 4 services |
| `deploy/compose.ghcr.yml` | Same from GHCR — the one-command install |
| `deploy/.env.example` | The packaged config contract |
| `deploy/keycloak/realm-spire.json` | Shipped realm, issuer-pinned |
| `deploy/e2e.sh` | The round-trip check, shared by `e2e.yml` and `release.yml` |
| `deploy/helm/spire/**` | The chart — single source of truth |
| `deploy/helm/spire/tests/render.sh` | The invariant assertions + `--self-test` |
| `deploy/kustomize/**` | Inflates the chart |
| `deploy/k8s/{simple,production}/spire.yaml` | Rendered, drift-checked |
| `deploy/render-manifests.sh` | Generator with `--check` |
| `.github/workflows/*.yml` | Nine workflows |
| `.github/dependabot.yml` | Update config (not a workflow) |

---

### Task 1: Gradle test tiers and their coverage guard

The fast/slow split lives in Gradle, not YAML, so it stays runnable locally and can be guarded. Without the guard a new module joins neither tier, is tested by nothing, and CI stays green — the `ContractSchemaSnapshotTest` vacuity shape.

**Files:**
- Modify: `build.gradle.kts` (append after the `cleanDevServices` task)
- Create: `spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: Gradle tasks `testFast` and `testServices`, invoked by `ci.yml` in Task 2. Two `val` declarations in the root build read by the guard as source text: `fastTestModules` and `serviceTestModules`, each a `listOf("spire-…")`.

- [ ] **Step 1: Write the failing guard test**

`spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java`:

```java
package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every module that has tests belongs to exactly one CI tier.
 *
 * <p>CI runs {@code testFast} (Docker-free modules) and {@code testServices} (the three deployables,
 * whose {@code @QuarkusTest}s boot Dev Services containers). A module in neither tier is compiled by
 * the build and <em>tested by nothing</em>, while CI stays green — the same vacuity as a snapshot gate
 * that iterates an empty list and reports zero failures.
 *
 * <p>Reads the two lists as source text rather than querying Gradle's model, which is how every other
 * guard in this module works and keeps the check runnable from a plain test JVM.
 */
class TestTierCoverageTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\(\"([^\"]+)\"\\)", Pattern.MULTILINE);

    @Test
    void everyModuleWithTestsIsInExactlyOneTier() throws IOException {
        Set<String> fast = tierList("fastTestModules");
        Set<String> services = tierList("serviceTestModules");

        List<String> unassigned = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (String module : includedModules()) {
            if (!hasTests(module)) {
                continue;
            }
            boolean inFast = fast.contains(module);
            boolean inServices = services.contains(module);
            if (inFast && inServices) {
                both.add(module);
            } else if (!inFast && !inServices) {
                unassigned.add(module);
            }
        }

        assertTrue(unassigned.isEmpty(),
                "These modules have tests but belong to no CI tier, so CI never runs them: " + unassigned
                        + ". Add each to fastTestModules or serviceTestModules in the root build.gradle.kts.");
        assertTrue(both.isEmpty(), "These modules are in both CI tiers, so their tests run twice: " + both);
    }

    @Test
    void neitherTierNamesAModuleThatIsNotIncluded() throws IOException {
        Set<String> included = includedModules();
        for (String tier : List.of("fastTestModules", "serviceTestModules")) {
            for (String module : tierList(tier)) {
                assertTrue(included.contains(module),
                        tier + " names '" + module + "', which settings.gradle.kts does not include. "
                                + "A stale entry silently drops that module's tests from CI.");
            }
        }
    }

    /** Guards the guard: a parser that silently matches nothing would pass every assertion above. */
    @Test
    void theListsWereActuallyFound() throws IOException {
        assertFalse(tierList("fastTestModules").isEmpty(), "fastTestModules parsed to nothing");
        assertEquals(3, tierList("serviceTestModules").size(), "expected exactly the three deployables");
        assertTrue(includedModules().size() > 10, "settings.gradle.kts parsed to too few modules");
    }

    private static Set<String> tierList(String name) throws IOException {
        String build = Files.readString(ROOT.resolve("build.gradle.kts"));
        Matcher declaration = Pattern
                .compile("val\\s+" + name + "\\s*=\\s*listOf\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(build);
        assertTrue(declaration.find(), "no `val " + name + " = listOf(...)` in the root build.gradle.kts");
        Set<String> modules = new LinkedHashSet<>();
        Matcher entry = Pattern.compile("\"([^\"]+)\"").matcher(declaration.group(1));
        while (entry.find()) {
            modules.add(entry.group(1));
        }
        return modules;
    }

    private static Set<String> includedModules() throws IOException {
        String settings = Files.readString(ROOT.resolve("settings.gradle.kts"));
        Set<String> modules = new LinkedHashSet<>();
        Matcher matcher = INCLUDE.matcher(settings);
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }
        return modules;
    }

    private static boolean hasTests(String module) throws IOException {
        Path tests = ROOT.resolve(module).resolve("src/test/java");
        if (!Files.isDirectory(tests)) {
            return false;
        }
        try (var walk = Files.walk(tests)) {
            return walk.anyMatch(p -> p.toString().endsWith(".java"));
        }
    }
}
```

- [ ] **Step 2: Run it and verify it fails**

Run: `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`
Expected: FAIL — `no \`val fastTestModules = listOf(...)\` in the root build.gradle.kts`.

- [ ] **Step 3: Add the tiers to the root build**

Append to `build.gradle.kts`:

```kotlin
/**
 * The two CI test tiers. The split is by Docker requirement: the modules below run on a bare JVM,
 * while the three deployables' @QuarkusTests boot Postgres + Kafka Dev Services containers and are
 * the slow half of the suite.
 *
 * Declared here rather than as a list of module paths in a workflow file for two reasons: it stays
 * runnable locally as a pre-commit loop, and TestTierCoverageTest can guard it — a module in neither
 * tier is tested by nothing while CI stays green.
 */
val fastTestModules = listOf(
    "spire-contract",
    "spire-arch",
    "spire-encryption",
    "spire-diff",
    "spire-http",
    "spire-llm",
    "spire-scm-bitbucket",
    "spire-scm-github",
    "spire-scm-gitlab",
    "spire-context-jira",
    "spire-context-confluence",
    "spire-context-github",
    "spire-context-gitlab",
)

val serviceTestModules = listOf(
    "spire-gateway",
    "spire-orchestrator",
    "spire-review-worker",
)

tasks.register("testFast") {
    group = "verification"
    description = "Runs every module whose tests need no Docker. CI's fast job."
    dependsOn(fastTestModules.map { ":$it:test" })
}

tasks.register("testServices") {
    group = "verification"
    description = "Runs the three deployables' tests (Quarkus Dev Services: Postgres + Kafka)."
    dependsOn(serviceTestModules.map { ":$it:test" })
}
```

- [ ] **Step 4: Run the guard and the tasks**

Run: `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`
Expected: PASS (3 tests).

Run: `./gradlew testFast`
Expected: PASS. Note the wall-clock time — this is the number `ci.yml`'s fast job is budgeted against.

- [ ] **Step 5: Mutation-verify the guard**

Temporarily delete `"spire-diff",` from `fastTestModules`, run `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`, and confirm **exactly one** test fails (`everyModuleWithTestsIsInExactlyOneTier`) naming `spire-diff`. Restore the line.

Then temporarily add `"spire-nonexistent",` to `fastTestModules` and confirm `neitherTierNamesAModuleThatIsNotIncluded` fails. Restore.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java
git commit -m "Split the test suite into CI tiers, and guard the split"
```

---

### Task 2: `ci.yml`

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `testFast` / `testServices` from Task 1.
- Produces: a workflow named `ci` with jobs `fast`, `ui`, `services`. Task 12's auto-merge waits on this name.

- [ ] **Step 1: Write the workflow**

`.github/workflows/ci.yml`:

```yaml
# Fast feedback on every change. Three jobs, split by what they need:
#   fast     — 13 modules that run on a bare JVM. Target under 3 minutes.
#   ui       — exactly the three commands spire-ui already has. No linter is added here.
#   services — the three deployables, whose tests boot Postgres + Kafka Dev Services containers,
#              plus `assemble` because ONLY packaging catches a wrong daemon JVM (class file 69.0).
name: ci

on:
  pull_request:
  push:
    branches: [master]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  fast:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew testFast --no-daemon

  ui:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: spire-ui
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: spire-ui/package-lock.json
      - run: npm ci
      - run: npx tsc --noEmit
      - run: npm run test
      - run: npm run build

  services:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
      # Dev Services needs Docker; ubuntu-latest provides it.
      - run: ./gradlew testServices --no-daemon
      # `assemble` triggers quarkusBuild. `build` would re-run the fast tier the first job covered.
      - run: ./gradlew assemble --no-daemon
```

- [ ] **Step 2: Validate the YAML parses**

Run: `docker run --rm -v "$(git rev-parse --show-toplevel):/w" -w //w mikefarah/yq:4 e '.jobs | keys' .github/workflows/ci.yml`
Expected: `- fast`, `- ui`, `- services`.

- [ ] **Step 3: Confirm the commands work locally**

Run: `./gradlew testFast --no-daemon` then `cd spire-ui && npx tsc --noEmit && npm run test`
Expected: both green. If `npm run test` is red, stop — the workflow is not the problem.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "Verify every change on CI, split by what each tier needs"
```

---

### Task 3: Production image for the three Quarkus services

**Files:**
- Create: `Dockerfile`
- Modify: `.dockerignore`

**Interfaces:**
- Consumes: nothing.
- Produces: an image accepting `--build-arg SERVICE=gateway|orchestrator|review-worker`, listening on **8080**, health at `/q/health/ready`. Tasks 6, 7, 8, 11 build it.

- [ ] **Step 1: Write the Dockerfile**

`Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
#
# Production image for the three Quarkus services. ONE Dockerfile, parameterised by SERVICE —
# matching Dockerfile.dev, which is already one image parameterised by compose.
#
#   docker build --build-arg SERVICE=gateway -t spire-gateway .
#
ARG SERVICE

FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /workspace

# Build files first so the dependency layer caches independently of source edits.
COPY gradlew settings.gradle.kts gradle.properties build.gradle.kts ./
COPY gradle/ gradle/
COPY spire-arch/build.gradle.kts spire-arch/
COPY spire-contract/build.gradle.kts spire-contract/
COPY spire-context-confluence/build.gradle.kts spire-context-confluence/
COPY spire-context-github/build.gradle.kts spire-context-github/
COPY spire-context-gitlab/build.gradle.kts spire-context-gitlab/
COPY spire-context-jira/build.gradle.kts spire-context-jira/
COPY spire-diff/build.gradle.kts spire-diff/
COPY spire-encryption/build.gradle.kts spire-encryption/
COPY spire-gateway/build.gradle.kts spire-gateway/
COPY spire-http/build.gradle.kts spire-http/
COPY spire-llm/build.gradle.kts spire-llm/
COPY spire-orchestrator/build.gradle.kts spire-orchestrator/
COPY spire-review-worker/build.gradle.kts spire-review-worker/
COPY spire-scm-bitbucket/build.gradle.kts spire-scm-bitbucket/
COPY spire-scm-github/build.gradle.kts spire-scm-github/
COPY spire-scm-gitlab/build.gradle.kts spire-scm-gitlab/

# A Windows checkout gives gradlew CRLF, and /bin/sh then rejects the shebang with
# "bad interpreter: /bin/sh^M". CI on Linux never sees this; a local `deploy/compose.yml`
# build does. Same normalisation Dockerfile.dev applies.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon --console=plain :spire-${SERVICE}:dependencies --configuration runtimeClasspath > /dev/null

COPY . .
RUN sed -i 's/\r$//' gradlew \
 && find . -path '*/db/migration/*.sql' -exec sed -i 's/\r$//' {} +
# Tests already ran in ci.yml. Re-running here would run them once per architecture under QEMU.
RUN ./gradlew --no-daemon --console=plain :spire-${SERVICE}:build -x test

FROM eclipse-temurin:25-jre-alpine
ARG SERVICE

LABEL org.opencontainers.image.source="https://github.com/artyomsv/code-spire" \
      org.opencontainers.image.licenses="FSL-1.1-ALv2" \
      org.opencontainers.image.title="spire-${SERVICE}"

# Each service's application.yml sets quarkus.http.port from its own ${*_HTTP_PORT:3408x} default.
# This env var targets quarkus.http.port directly and beats that default, so all three images listen
# where EXPOSE and HEALTHCHECK say they do. Without it the container is unhealthy forever and
# compose's `depends_on: service_healthy` never releases.
ENV QUARKUS_HTTP_PORT=8080 \
    QUARKUS_HTTP_HOST=0.0.0.0

RUN addgroup -g 1001 spire && adduser -u 1001 -G spire -s /bin/sh -D spire
WORKDIR /app

# The fast-jar in four layers, lib/ first: it is hundreds of MB of unchanging dependencies while
# app/ is ~1 MB of our classes. Copied as one directory, every code change re-pushes everything.
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/lib/ ./lib/
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/*.jar ./
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/app/ ./app/
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/quarkus/ ./quarkus/
COPY --chown=1001:1001 LICENSE NOTICE ./

USER 1001
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/q/health/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
```

- [ ] **Step 2: Extend `.dockerignore` for the production context**

The current file is tuned for `Dockerfile.dev`. Append:

```
# Production build context (Dockerfile): the deploy tree and docs never enter an image.
deploy
docs
techdebt
licenses
infra
```

Keep every existing line — `.env`, `.env.*`, `.handoff` and `*.dump` must stay excluded.

- [ ] **Step 3: Build one image and prove the port**

```bash
docker build --build-arg SERVICE=orchestrator -t spire-orchestrator:local .
docker run --rm -d --name spire-port-probe spire-orchestrator:local
sleep 25
docker exec spire-port-probe wget -qO- http://localhost:8080/q/health/live
docker rm -f spire-port-probe
```

Expected: the health JSON on **8080**. The service will not reach *ready* without a database — `live` is the correct probe here. If this returns nothing, `QUARKUS_HTTP_PORT` is not winning over `application.yml` and the rest of the plan is built on sand: stop and fix it.

- [ ] **Step 4: Build the other two**

```bash
docker build --build-arg SERVICE=gateway -t spire-gateway:local .
docker build --build-arg SERVICE=review-worker -t spire-review-worker:local .
docker images | grep spire-
```

Expected: three images. Record their sizes in the commit body — §5.4 predicts large, and a real number is worth having.

- [ ] **Step 5: Commit**

```bash
git add Dockerfile .dockerignore
git commit -m "Package the three services as production images"
```

---

### Task 4: The single-origin nginx image

This is the security-critical task in the plan. Read the spec's nginx section before starting.

**Files:**
- Create: `spire-ui/nginx/default.conf.template`
- Create: `spire-ui/Dockerfile`
- Create: `spire-ui/.dockerignore`

**Interfaces:**
- Consumes: nothing.
- Produces: an image listening on **8080** that reads `SPIRE_ORCHESTRATOR_URL`, `SPIRE_GATEWAY_URL`, `SPIRE_WORKER_URL`. Task 9's `render.sh` asserts over `spire-ui/nginx/default.conf.template` by path.

- [ ] **Step 1: Write the nginx template**

`spire-ui/nginx/default.conf.template`:

```nginx
# The single origin. ADR-022 scopes each service's session cookie to its own URL path, which only
# isolates anything while all four services answer on ONE origin — in dev that origin is the Vite
# proxy (see spire-ui/vite.config.ts); in a packaged run it is this file.
#
# Substituted at container start by the nginx image's entrypoint. NGINX_ENVSUBST_FILTER limits it to
# SPIRE_-prefixed names so nginx's own $host / $scheme / $http_* survive untouched.

# Never clobber an upstream TLS terminator. Behind a Kubernetes Ingress that terminates TLS and
# forwards over http, $scheme here is "http": setting X-Forwarded-Proto from it would overwrite the
# controller's "https", and the services (which trust forwarded headers) would mint an http://
# redirect_uri and drop the session cookie's Secure attribute. That breaks ONLY behind TLS, so a
# plaintext compose check passes clean.
map $http_x_forwarded_proto $spire_fwd_proto {
    default $http_x_forwarded_proto;
    ''      $scheme;
}

server {
    listen 8080;
    server_name _;

    client_max_body_size 25m;   # SCM webhook payloads for a large PR

    proxy_set_header Host              $host;   # NEVER rewrite: redirect_uri is derived from it
    proxy_set_header X-Forwarded-Proto $spire_fwd_proto;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Host  $host;

    # SCM ingress. MUST come before the SPA fallback: a delivery that fell through to try_files
    # would be answered 200 with index.html, so the SCM records success, never retries, and the
    # review is lost with nothing logged anywhere.
    location /webhooks {
        proxy_pass ${SPIRE_GATEWAY_URL};
    }

    # The orchestrator owns /api, its three WebSockets included (/api/ws/*).
    location /api {
        proxy_pass ${SPIRE_ORCHESTRATOR_URL};
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;   # nginx defaults to 60s and would cut idle sockets
    }

    # The gateway owns /gw — its registry API and its attention socket.
    location /gw {
        proxy_pass ${SPIRE_GATEWAY_URL};
        proxy_http_version 1.1;
        proxy_set_header Upgrade    $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }

    # The worker owns /wk — a review's assembled context.
    location /wk {
        proxy_pass ${SPIRE_WORKER_URL};
    }

    location = /healthz {
        access_log off;
        return 200 "ok\n";
    }

    # The dashboard is a client-routed SPA and must sit at the origin ROOT: every redirect target in
    # the services is "/" and the cookie paths are absolute.
    location / {
        root /usr/share/nginx/html;
        try_files $uri /index.html;
    }
}
```

- [ ] **Step 2: Write the UI Dockerfile**

`spire-ui/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
#
# The dashboard image: static assets AND the reverse proxy that puts all four services on one
# origin. That proxy is a security control, not a convenience — see nginx/default.conf.template.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine

LABEL org.opencontainers.image.source="https://github.com/artyomsv/code-spire" \
      org.opencontainers.image.licenses="FSL-1.1-ALv2" \
      org.opencontainers.image.title="spire-ui"

# Only SPIRE_-prefixed vars are substituted, so nginx's $host/$scheme/$http_* are left alone.
ENV NGINX_ENVSUBST_FILTER=^SPIRE_ \
    SPIRE_ORCHESTRATOR_URL=http://orchestrator:8080 \
    SPIRE_GATEWAY_URL=http://gateway:8080 \
    SPIRE_WORKER_URL=http://worker:8080

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
COPY LICENSE /LICENSE

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/healthz || exit 1
```

Note the three upstream defaults are in-network service names, which is genuine wiring rather than a
credential — the no-defaults rule covers secrets and environment-specific URLs, and these are
overridden by every deploy artifact.

- [ ] **Step 3: Write `spire-ui/.dockerignore`**

```
node_modules
dist
.env
.env.*
*.log
```

- [ ] **Step 4: Build and prove the rendered config**

```bash
docker build -t spire-ui:local ./spire-ui
docker run --rm -d --name spire-nginx-probe -p 34999:8080 spire-ui:local
sleep 3
docker exec spire-nginx-probe cat /etc/nginx/conf.d/default.conf | grep -E 'proxy_pass|\$host|\$spire_fwd_proto'
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:34999/
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:34999/healthz
docker rm -f spire-nginx-probe
```

Expected: three `proxy_pass http://…:8080` lines with the env values substituted; `$host` and
`$spire_fwd_proto` still literal `$`-variables (**not** substituted — if they are empty, the envsubst
filter is wrong); `200` for both curls.

- [ ] **Step 5: Prove `/webhooks` does not reach the SPA**

```bash
docker run --rm -d --name spire-nginx-probe -p 34999:8080 spire-ui:local
sleep 3
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:34999/webhooks/github/TEST-key
docker rm -f spire-nginx-probe
```

Expected: **502** (no gateway behind it). A **200** means the request reached `try_files` and the
SPA answered — the exact defect this task exists to prevent.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/Dockerfile spire-ui/.dockerignore spire-ui/nginx/default.conf.template
git commit -m "Serve the dashboard and its four prefixes from one origin"
```

---

### Task 5: Prod-profile proxy and cookie properties

**Files:**
- Modify: `spire-gateway/src/main/resources/application.yml`
- Modify: `spire-orchestrator/src/main/resources/application.yml`
- Modify: `spire-review-worker/src/main/resources/application.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: a `%prod` profile block in each service. Task 9's `render.sh` reads the cookie paths from these same files.

- [ ] **Step 1: Add the block to each service**

Add a `"%prod"` profile section to each of the three files (create it if absent; these files already
carry `"%dev"` and `"%test"` sections, so follow their formatting and 2-space indent):

```yaml
"%prod":
  quarkus:
    http:
      proxy:
        # The dashboard image is the only ingress; without this the services see the proxy's address
        # rather than the client's, and OIDC derives redirect_uri from the wrong host.
        proxy-address-forwarding: true
        # Paired deliberately. D10-AUTH-PLAN.md records proxy-address-forwarding as a header-spoofing
        # vector while service ports stay directly reachable: unpaired, anything that can reach this
        # port forges X-Forwarded-For/-Proto. No deploy artifact publishes a service port, and
        # deploy/helm/spire/tests/render.sh asserts that.
        trusted-proxies: ${SPIRE_TRUSTED_PROXIES}
    oidc:
      authentication:
        # A TLS terminator that forwards over http would otherwise yield a cookie without Secure.
        cookie-force-secure: true
```

`SPIRE_TRUSTED_PROXIES` has no default on purpose — the correct value is the UI's network CIDR and
is topology-specific.

- [ ] **Step 2: Verify prod config resolves**

Run: `./gradlew :spire-orchestrator:assemble --no-daemon`
Expected: BUILD SUCCESSFUL. A malformed YAML key fails here.

- [ ] **Step 3: Verify the three cookie paths are still distinct**

Run: `grep -n "cookie-path" spire-*/src/main/resources/application.yml`
Expected: exactly three lines — `/gw`, `/api`, `/wk`. Task 9 asserts this mechanically; this step is
so the next task starts from a known state.

- [ ] **Step 4: Run the service tests**

Run: `./gradlew testServices --no-daemon`
Expected: PASS. `%prod` is inert under `%test`, so a failure here means a YAML structure error.

- [ ] **Step 5: Commit**

```bash
git add spire-gateway/src/main/resources/application.yml \
        spire-orchestrator/src/main/resources/application.yml \
        spire-review-worker/src/main/resources/application.yml
git commit -m "Trust the dashboard proxy, and only it, in prod"
```

---

### Task 6: `docker.yml` — build, scan, publish `:edge`

**Files:**
- Create: `.github/workflows/docker.yml`

**Interfaces:**
- Consumes: `Dockerfile` (Task 3), `spire-ui/Dockerfile` (Task 4).
- Produces: `ghcr.io/artyomsv/spire-{gateway,orchestrator,review-worker,ui}:edge` and `:sha-<short>`. Tasks 7 and 8 pull `:edge`.

- [ ] **Step 1: Write the workflow**

`.github/workflows/docker.yml`:

```yaml
# Builds all four images on master, scans them, and publishes :edge.
#
# It publishes on purpose. The prior analysis (§2) copied a build-but-do-not-push precedent, but
# e2e.yml consumes GHCR images: with releases as the only publisher, the topology check could not run
# until after the release it exists to protect, and a nightly e2e would exercise the last release
# rather than current master. :edge also happens to be the tag someone tracking the tip wants.
name: docker

on:
  push:
    branches: [master]

permissions:
  contents: read
  packages: write
  security-events: write

concurrency:
  group: docker-${{ github.ref }}
  cancel-in-progress: true

jobs:
  images:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        include:
          - name: spire-gateway
            context: .
            dockerfile: Dockerfile
            service: gateway
          - name: spire-orchestrator
            context: .
            dockerfile: Dockerfile
            service: orchestrator
          - name: spire-review-worker
            context: .
            dockerfile: Dockerfile
            service: review-worker
          - name: spire-ui
            context: ./spire-ui
            dockerfile: ./spire-ui/Dockerfile
            service: ""
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # amd64 only here. arm64 under QEMU costs tens of minutes (§5.4) and belongs in release.yml.
      - name: Build
        uses: docker/build-push-action@v6
        with:
          context: ${{ matrix.context }}
          file: ${{ matrix.dockerfile }}
          build-args: ${{ matrix.service && format('SERVICE={0}', matrix.service) || '' }}
          platforms: linux/amd64
          load: true
          tags: ghcr.io/artyomsv/${{ matrix.name }}:edge
          cache-from: type=gha,scope=${{ matrix.name }}
          cache-to: type=gha,scope=${{ matrix.name }},mode=max

      # Report-only, per §2's recorded reasoning: the residual HIGH/CRITICALs on a fully patched
      # image are language-stdlib CVEs fixed only in unreleased toolchains — an advisory treadmill no
      # bump can clear. Fixable module/OS CVEs are bumped promptly and triaged from the Security tab.
      - name: Scan
        uses: aquasecurity/trivy-action@0.28.0
        with:
          image-ref: ghcr.io/artyomsv/${{ matrix.name }}:edge
          format: sarif
          output: trivy-${{ matrix.name }}.sarif
          exit-code: '0'
          severity: HIGH,CRITICAL
      - uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: trivy-${{ matrix.name }}.sarif
          category: trivy-${{ matrix.name }}

      - name: Push
        run: |
          docker tag ghcr.io/artyomsv/${{ matrix.name }}:edge \
                     ghcr.io/artyomsv/${{ matrix.name }}:sha-${GITHUB_SHA::7}
          docker push ghcr.io/artyomsv/${{ matrix.name }}:edge
          docker push ghcr.io/artyomsv/${{ matrix.name }}:sha-${GITHUB_SHA::7}
```

- [ ] **Step 2: Validate the matrix parses**

Run: `docker run --rm -v "$(git rev-parse --show-toplevel):/w" -w //w mikefarah/yq:4 e '.jobs.images.strategy.matrix.include[].name' .github/workflows/docker.yml`
Expected: the four image names.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/docker.yml
git commit -m "Build, scan and publish an edge image per service"
```

- [ ] **Step 4: Note the one manual step**

GHCR packages default to **private** on first push even from a public repository. After this workflow
first succeeds, set each of the four packages to public (or link them to the repo) once, by hand.
Record it in `deploy/README.md` in Task 7.

---

### Task 7: The packaged compose stack

**Files:**
- Create: `deploy/compose.ghcr.yml`
- Create: `deploy/compose.yml`
- Create: `deploy/.env.example`
- Create: `deploy/keycloak/realm-spire.json`
- Create: `deploy/README.md`

**Interfaces:**
- Consumes: `:edge` images (Task 6).
- Produces: a stack on host ports `34700` (UI) and `34767` (Keycloak). Task 8's `deploy/e2e.sh` drives it.

- [ ] **Step 1: Write the config contract**

`deploy/.env.example`:

```bash
# ---------------------------------------------------------------------------
# Packaged deployment contract. Copy to .env and fill in. Every value here is
# required and has NO default — a missing one fails the service at startup,
# naming the variable. Never put an inline "# comment" after a value.
#
# THIS STACK HAS NO TLS. Session cookies travel in plaintext and are sniffable
# and replayable. Bind it to localhost or put a TLS terminator in front. This
# stops casual access; it does not stop an on-path attacker.
# ---------------------------------------------------------------------------

# --- Host ports (30000-49999 range) ---
SPIRE_UI_PORT=34700
SPIRE_KEYCLOAK_PORT=34767

# --- Postgres ---
POSTGRES_DB=spire
POSTGRES_USER=spire
POSTGRES_PASSWORD=CHANGE_ME
# The gateway owns ONLY the `gateway` schema, so a compromised internet-facing edge can verify
# webhook signatures but cannot read the encrypted SCM/LLM token registry or the event store.
GATEWAY_POSTGRES_USER=gateway
GATEWAY_POSTGRES_PASSWORD=CHANGE_ME

# --- Encryption at rest (Tink AES-256-GCM), base64 keysets ---
# Generate your own. TWO SEPARATE keysets: the gateway never holds the master one.
# NEVER let a deployment tool generate these -- they decrypt existing rows, so a regenerated
# keyset makes every encrypted event payload, provider secret and context blob unreadable.
SPIRE_ENCRYPTION_KEYSET=REPLACE_ME
SPIRE_ENCRYPTION_WEBHOOK_KEYSET=REPLACE_ME

# --- Operator authentication ---
# This file bundles Keycloak and imports keycloak/realm-spire.json. To use your own identity
# provider instead, point this at it and remove the keycloak service. The issuer must be
# reachable under the SAME name from both the browser and the containers.
SPIRE_OIDC_AUTH_SERVER_URL=http://host.docker.internal:34767/realms/spire
# Distinct per service. Collapsing them into one ends the per-service isolation silently.
SPIRE_OIDC_ORCHESTRATOR_SECRET=CHANGE_ME
SPIRE_OIDC_GATEWAY_SECRET=CHANGE_ME
SPIRE_OIDC_WORKER_SECRET=CHANGE_ME

# Bundled Keycloak admin. Administers the local IdP and nothing else.
KEYCLOAK_ADMIN_USER=CHANGE_ME
KEYCLOAK_ADMIN_PASSWORD=CHANGE_ME

# --- Trusted proxy ---
# The dashboard container's network. proxy-address-forwarding is a spoofing vector unpaired.
SPIRE_TRUSTED_PROXIES=172.16.0.0/12
```

- [ ] **Step 2: Copy the realm and pin its issuer**

```bash
mkdir -p deploy/keycloak
cp infra/keycloak/realm-spire.json deploy/keycloak/realm-spire.json
```

The realm's three clients each need a redirect URI valid for the packaged origin. Add
`http://localhost:34700/*` to every client's `redirectUris` and `http://localhost:34700` to
`webOrigins`, keeping the existing dev entries. Leave the three `dev-only-*-secret` values as they
are — they are obviously-synthetic placeholders the compose file overrides.

- [ ] **Step 3: Write `deploy/compose.ghcr.yml`**

```yaml
# The one-command install. Pulls published images; builds nothing.
#
#   cp deploy/.env.example deploy/.env    # fill it in
#   docker compose -f deploy/compose.ghcr.yml up -d
#
# NO TLS. Bind to localhost or front it with a terminator. See deploy/README.md.
name: spire

x-service-env: &service-env
  QUARKUS_PROFILE: prod
  QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:?set in .env}
  KAFKA_BOOTSTRAP_SERVERS: redpanda:9092
  SPIRE_OIDC_AUTH_SERVER_URL: ${SPIRE_OIDC_AUTH_SERVER_URL:?set in .env}
  SPIRE_TRUSTED_PROXIES: ${SPIRE_TRUSTED_PROXIES:?set in .env}

services:
  redpanda:
    image: redpandadata/redpanda:v26.1.12
    command:
      - redpanda
      - start
      - --overprovisioned
      - --smp=1
      - --memory=1G
      - --reserve-memory=0M
      - --node-id=0
      - --check=false
      - --kafka-addr=INTERNAL://0.0.0.0:9092
      - --advertise-kafka-addr=INTERNAL://redpanda:9092
    volumes:
      - redpanda:/var/lib/redpanda/data
    healthcheck:
      test: ["CMD-SHELL", "rpk cluster health --exit-when-healthy || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 6
    logging: &logging
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"

  postgres:
    image: postgres:18.4-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:?set in .env}
      POSTGRES_USER: ${POSTGRES_USER:?set in .env}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
      GATEWAY_POSTGRES_USER: ${GATEWAY_POSTGRES_USER:?set in .env}
      GATEWAY_POSTGRES_PASSWORD: ${GATEWAY_POSTGRES_PASSWORD:?set in .env}
    volumes:
      - pgdata:/var/lib/postgresql
      - ../infra/postgres-init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 10s
      timeout: 3s
      retries: 5
    logging: *logging

  keycloak:
    image: quay.io/keycloak/keycloak:26.5.5
    command: ["start-dev", "--import-realm", "--http-port=8080"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KEYCLOAK_ADMIN_USER:?set in .env}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:?set in .env}
      # Pinned so the issuer stays fixed while front- and backchannel differ. An unpinned instance
      # derives its issuer from the Host it was reached by, so browser and containers disagree and
      # every token fails validation.
      KC_HOSTNAME: http://host.docker.internal:${SPIRE_KEYCLOAK_PORT:-34767}
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HEALTH_ENABLED: "true"
    volumes:
      - ./keycloak:/opt/keycloak/data/import:ro
    ports:
      - "${SPIRE_KEYCLOAK_PORT:-34767}:8080"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/9000 && echo -e 'GET /health/ready HTTP/1.1\\r\\nHost: x\\r\\nConnection: close\\r\\n\\r\\n' >&3 && cat <&3 | grep -q UP"]
      interval: 10s
      timeout: 5s
      retries: 12
    logging: *logging

  orchestrator:
    image: ghcr.io/artyomsv/spire-orchestrator:${SPIRE_VERSION:-edge}
    depends_on:
      postgres: {condition: service_healthy}
      redpanda: {condition: service_healthy}
      keycloak: {condition: service_healthy}
    environment:
      <<: *service-env
      QUARKUS_DATASOURCE_USERNAME: ${POSTGRES_USER:?set in .env}
      QUARKUS_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
      SPIRE_ENCRYPTION_KEYSET: ${SPIRE_ENCRYPTION_KEYSET:?set in .env}
      SPIRE_OIDC_CLIENT_ID: spire-orchestrator
      SPIRE_OIDC_CLIENT_SECRET: ${SPIRE_OIDC_ORCHESTRATOR_SECRET:?set in .env}
    extra_hosts:
      - "host.docker.internal:host-gateway"
    logging: *logging

  gateway:
    image: ghcr.io/artyomsv/spire-gateway:${SPIRE_VERSION:-edge}
    depends_on:
      postgres: {condition: service_healthy}
      redpanda: {condition: service_healthy}
      keycloak: {condition: service_healthy}
    environment:
      <<: *service-env
      # The schema-scoped role, never the owner of the orchestrator schema.
      QUARKUS_DATASOURCE_USERNAME: ${GATEWAY_POSTGRES_USER:?set in .env}
      QUARKUS_DATASOURCE_PASSWORD: ${GATEWAY_POSTGRES_PASSWORD:?set in .env}
      # The dedicated webhook keyset. The gateway never receives SPIRE_ENCRYPTION_KEYSET.
      SPIRE_ENCRYPTION_WEBHOOK_KEYSET: ${SPIRE_ENCRYPTION_WEBHOOK_KEYSET:?set in .env}
      SPIRE_OIDC_CLIENT_ID: spire-gateway
      SPIRE_OIDC_CLIENT_SECRET: ${SPIRE_OIDC_GATEWAY_SECRET:?set in .env}
    extra_hosts:
      - "host.docker.internal:host-gateway"
    logging: *logging

  worker:
    image: ghcr.io/artyomsv/spire-review-worker:${SPIRE_VERSION:-edge}
    depends_on:
      postgres: {condition: service_healthy}
      redpanda: {condition: service_healthy}
      keycloak: {condition: service_healthy}
    environment:
      <<: *service-env
      QUARKUS_DATASOURCE_USERNAME: ${POSTGRES_USER:?set in .env}
      QUARKUS_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:?set in .env}
      SPIRE_ENCRYPTION_KEYSET: ${SPIRE_ENCRYPTION_KEYSET:?set in .env}
      SPIRE_OIDC_CLIENT_ID: spire-review-worker
      SPIRE_OIDC_CLIENT_SECRET: ${SPIRE_OIDC_WORKER_SECRET:?set in .env}
    extra_hosts:
      - "host.docker.internal:host-gateway"
    logging: *logging

  # The ONLY published application port. No service port is exposed, which is what makes
  # trusted-proxies meaningful.
  ui:
    image: ghcr.io/artyomsv/spire-ui:${SPIRE_VERSION:-edge}
    depends_on: [orchestrator, gateway, worker]
    environment:
      SPIRE_ORCHESTRATOR_URL: http://orchestrator:8080
      SPIRE_GATEWAY_URL: http://gateway:8080
      SPIRE_WORKER_URL: http://worker:8080
    ports:
      - "${SPIRE_UI_PORT:-34700}:8080"
    logging: *logging

volumes:
  pgdata:
  redpanda:
```

- [ ] **Step 4: Write `deploy/compose.yml`**

Copy `compose.ghcr.yml` and replace each of the four `image:` lines with a `build:` block, keeping
everything else identical:

```yaml
  orchestrator:
    build:
      context: ..
      dockerfile: Dockerfile
      args:
        SERVICE: orchestrator
    image: spire-orchestrator:local
```

`gateway` → `SERVICE: gateway`, `worker` → `SERVICE: review-worker`, and:

```yaml
  ui:
    build:
      context: ../spire-ui
      dockerfile: Dockerfile
    image: spire-ui:local
```

- [ ] **Step 5: Bring the stack up locally**

```bash
cp deploy/.env.example deploy/.env
# Fill in: passwords, both keysets, the three client secrets, the Keycloak admin.
# The three client secrets must match deploy/keycloak/realm-spire.json (dev-only-*-secret).
docker compose -f deploy/compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/compose.yml --env-file deploy/.env ps
```

Expected: all seven services running, `postgres`/`redpanda`/`keycloak` healthy. Then:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:34700/
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:34700/api/me
```

Expected: `200` and `200`. If the second is `502`, the orchestrator did not start — read its logs;
the likely cause is a missing env var, which is the config contract doing its job.

- [ ] **Step 6: Write `deploy/README.md`**

Cover, in this order: the no-TLS warning first and prominently; the one-command install; the two
presets; the four required secrets and how to generate the keysets; the BYO-IdP realm contract (three
clients, an audience mapper each, both roles `spire-viewer`/`spire-admin`, roles read from the
**access** token); the manual `CREATE ROLE`/`CREATE SCHEMA` pair for operators who will not hand a
chart superuser credentials (copy from `.env.example`); and the one-time GHCR package-visibility flip
from Task 6. Never describe the project as open source.

- [ ] **Step 7: Commit**

```bash
git add deploy/compose.yml deploy/compose.ghcr.yml deploy/.env.example \
        deploy/keycloak/realm-spire.json deploy/README.md
git commit -m "Ship a packaged stack that runs from one command"
```

---

### Task 8: `deploy/e2e.sh` and `e2e.yml`

The topology check. Everything else in this plan is a variation on something already verified locally; these lines are not.

**Files:**
- Create: `deploy/e2e.sh`
- Create: `.github/workflows/e2e.yml`

**Interfaces:**
- Consumes: `deploy/compose.ghcr.yml` (Task 7), `:edge` images (Task 6).
- Produces: `deploy/e2e.sh <base-url> <keycloak-url>`, exit 0 on success. Task 11 calls it too.

- [ ] **Step 1: Write the script**

`deploy/e2e.sh`:

```bash
#!/usr/bin/env bash
# End-to-end checks against a running packaged stack. Every line here is something no local dev run
# can prove, because dev has no reverse proxy: Vite supplies the single origin, and the webhook
# tunnel points straight at the gateway.
#
#   ./deploy/e2e.sh http://localhost:34700 http://localhost:34767
set -euo pipefail

BASE="${1:?usage: e2e.sh <base-url> <keycloak-url>}"
KC="${2:?usage: e2e.sh <base-url> <keycloak-url>}"
FAILED=0

check() {   # check <name> <expected-status> <curl args...>
    local name="$1" expected="$2"; shift 2
    local actual
    actual="$(curl -s -o /dev/null -w '%{http_code}' "$@")"
    if [ "$actual" = "$expected" ]; then
        echo "  PASS  $name ($actual)"
    else
        echo "  FAIL  $name — expected $expected, got $actual"
        FAILED=1
    fi
}

echo "== static and ingress =="
check "dashboard served"              200 "$BASE/"
# A signed-webhook path must reach the GATEWAY. If this returns 200 the request fell through to the
# SPA fallback: the SCM would record a successful delivery, never retry, and the review would be lost
# with nothing logged. 401 is the gateway rejecting an unsigned body — which is the gateway answering.
check "webhook reaches the gateway"   401 -X POST -H 'Content-Type: application/json' \
                                          -d '{"TEST":"unsigned"}' "$BASE/webhooks/github/TEST-key"
check "unknown path serves the SPA"   200 "$BASE/no-such-route"

echo "== policy through the proxy =="
check "api needs a session"           302 "$BASE/api/reviews"
check "me is public"                  200 "$BASE/api/me"
check "health is public"              200 "$BASE/q/health"

echo "== roles through the proxy =="
TOKEN="$(curl -s -X POST \
    "$KC/realms/spire/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=spire-orchestrator \
    -d "client_secret=${SPIRE_OIDC_ORCHESTRATOR_SECRET:?required}" \
    -d username=dev-viewer -d "password=${DEV_VIEWER_PASSWORD:?required}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
[ -n "$TOKEN" ] || { echo "  FAIL  could not obtain a viewer token"; exit 1; }

check "viewer reads reviews"          200 -H "Authorization: Bearer $TOKEN" "$BASE/api/reviews"
check "viewer refused configuration"  403 -H "Authorization: Bearer $TOKEN" "$BASE/api/providers"
check "viewer refused the dlq"        403 -H "Authorization: Bearer $TOKEN" "$BASE/api/dlq"

echo "== websocket upgrade traverses nginx =="
UPGRADE="$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    "$BASE/api/ws/timeline")"
if [ "$UPGRADE" = "101" ]; then
    echo "  PASS  /api/ws/timeline upgraded (101)"
else
    echo "  FAIL  /api/ws/timeline — expected 101, got $UPGRADE"
    FAILED=1
fi

echo "== per-prefix sessions exist =="
# Without these the gateway and worker screens are unreachable: neither fetch nor a WS handshake can
# follow the cross-origin redirect a missing sibling session produces.
check "gateway login endpoint"        302 "$BASE/gw/auth/login"
check "worker login endpoint"         302 "$BASE/wk/auth/login"

echo
if [ "$FAILED" -ne 0 ]; then
    echo "e2e FAILED"
    exit 1
fi
echo "e2e passed"
```

`chmod +x deploy/e2e.sh`.

The two login endpoints answer `302` (to the IdP) rather than `303` when called without a session —
that is the policy redirect, and it proves the route exists. A `404` would mean the prefix is not
routed.

- [ ] **Step 2: Add the privilege probe**

Append to `deploy/e2e.sh` before the final summary:

```bash
echo "== the gateway's privilege boundary =="
# The only check that tests what SECURITY.md actually cares about. Distinct usernames in the
# manifests and an existing role prove nothing about what that role can READ: an operator who fixes a
# permission error with a broad GRANT collapses the boundary with every piece of config still distinct.
PROBE="$(docker compose -f "$(dirname "$0")/compose.ghcr.yml" exec -T \
    -e PGPASSWORD="${GATEWAY_POSTGRES_PASSWORD:?required}" postgres \
    psql -U "${GATEWAY_POSTGRES_USER:?required}" -d "${POSTGRES_DB:?required}" \
    -tAc 'SELECT count(*) FROM orchestrator.review_status' 2>&1 || true)"
if echo "$PROBE" | grep -qi 'permission denied\|does not exist'; then
    echo "  PASS  gateway role cannot read the orchestrator schema"
else
    echo "  FAIL  gateway role READ the orchestrator schema: $PROBE"
    FAILED=1
fi
```

- [ ] **Step 3: Run it against the local stack**

With the Task 7 stack up:

```bash
set -a; . deploy/.env; set +a
export DEV_VIEWER_PASSWORD='<the realm’s dev-viewer password>'
./deploy/e2e.sh http://localhost:34700 http://localhost:34767
```

Expected: every line PASS. **Investigate any failure before continuing** — this script is the reason
the chart in Task 9 can be trusted.

- [ ] **Step 4: Mutation-verify the webhook check**

In `spire-ui/nginx/default.conf.template`, temporarily comment out the whole `location /webhooks`
block, rebuild the UI image (`docker compose -f deploy/compose.yml up -d --build ui`), and re-run the
script. Expected: exactly one FAIL — `webhook reaches the gateway — expected 401, got 200`. Restore
the block, rebuild, confirm green.

- [ ] **Step 5: Write the workflow**

`.github/workflows/e2e.yml`:

```yaml
# The topology check, against the published :edge images. Nightly and on demand — never on the PR
# path. Its subject is the packaged artifact, which is why it pulls rather than builds.
name: e2e

on:
  schedule:
    - cron: '17 3 * * *'
  workflow_dispatch:

permissions:
  contents: read
  packages: read

jobs:
  round-trip:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # Obviously-synthetic values. The realm ships the same client secrets, and no real credential
      # enters CI.
      - name: Write the environment
        run: |
          cat > deploy/.env <<'ENV'
          SPIRE_UI_PORT=34700
          SPIRE_KEYCLOAK_PORT=34767
          POSTGRES_DB=spire
          POSTGRES_USER=spire
          POSTGRES_PASSWORD=TEST-postgres-password
          GATEWAY_POSTGRES_USER=gateway
          GATEWAY_POSTGRES_PASSWORD=TEST-gateway-password
          SPIRE_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/spire
          SPIRE_OIDC_ORCHESTRATOR_SECRET=dev-only-orchestrator-secret
          SPIRE_OIDC_GATEWAY_SECRET=dev-only-gateway-secret
          SPIRE_OIDC_WORKER_SECRET=dev-only-worker-secret
          KEYCLOAK_ADMIN_USER=dev-admin
          KEYCLOAK_ADMIN_PASSWORD=TEST-keycloak-admin
          SPIRE_TRUSTED_PROXIES=172.16.0.0/12
          ENV
          # Two Tink AES-256-GCM keysets, generated per run so none is ever committed.
          echo "SPIRE_ENCRYPTION_KEYSET=$(./gradlew -q :spire-encryption:generateKeyset 2>/dev/null || openssl rand -base64 64 | tr -d '\n')" >> deploy/.env
          echo "SPIRE_ENCRYPTION_WEBHOOK_KEYSET=$(openssl rand -base64 64 | tr -d '\n')" >> deploy/.env

      - name: Up
        run: docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d --pull always

      - name: Wait for the dashboard
        run: |
          for i in $(seq 1 60); do
            if curl -fsS -o /dev/null http://localhost:34700/api/me; then exit 0; fi
            sleep 5
          done
          echo "dashboard never became reachable"; exit 1

      - name: Round-trip
        env:
          DEV_VIEWER_PASSWORD: dev-viewer-password
        run: |
          set -a; . deploy/.env; set +a
          ./deploy/e2e.sh http://localhost:34700 http://localhost:34767

      - if: failure()
        run: docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env logs --tail 200
      - if: always()
        run: docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env down -v
```

If `spire-encryption` has no keyset-generating Gradle task, drop that fallback and keep only
`openssl rand`; check with `./gradlew :spire-encryption:tasks --all | grep -i keyset` and use whatever
exists. A random 64-byte base64 string is **not** a valid Tink keyset — if the services reject it, add
a tiny generator task in `spire-encryption` rather than working around it here.

- [ ] **Step 6: Commit**

```bash
git add deploy/e2e.sh .github/workflows/e2e.yml
git commit -m "Prove the packaged topology end to end"
```

---

### Task 9: The Helm chart, the gateway-role Job, and the invariant assertions

**Files:**
- Create: `deploy/helm/spire/Chart.yaml`, `values.yaml`, `values-simple.yaml`, `values-production.yaml`
- Create: `deploy/helm/spire/templates/` — `_helpers.tpl`, `configmap.yaml`, `orchestrator.yaml`, `gateway.yaml`, `worker.yaml`, `ui.yaml`, `postgres.yaml`, `redpanda.yaml`, `keycloak.yaml`, `ingress.yaml`, `gateway-role-job.yaml`, `NOTES.txt`
- Create: `deploy/helm/spire/tests/render.sh`

**Interfaces:**
- Consumes: the images and env contract from Tasks 3, 4, 6, 7.
- Produces: a chart named `spire`; `tests/render.sh [--self-test]` exiting non-zero on a violated invariant. Task 10's `render-manifests.sh` renders the same chart.

- [ ] **Step 1: Write the assertions first**

`deploy/helm/spire/tests/render.sh`:

```bash
#!/usr/bin/env bash
# Invariants that exist ONLY as configuration shape, which is why no unit test reaches them:
# flattening them would fail nothing and break everything.
#
# The checks span TWO sources — rendered Helm output, and in-repo config files that are baked into
# images rather than templated. Treating the second group as manifest greps would make them vacuous.
#
#   ./tests/render.sh              assert the invariants hold
#   ./tests/render.sh --self-test  assert each check catches its own break
set -uo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$CHART/../../.." && pwd)"
NGINX="$REPO/spire-ui/nginx/default.conf.template"
FAILED=0

fail() { echo "  FAIL  $1"; FAILED=1; }
pass() { echo "  PASS  $1"; }

render() {   # render <values-file> [extra --set args...]
    helm template spire "$CHART" -f "$CHART/$1" "${@:2}" \
        --set secrets.existingSecret=TEST-spire-secrets \
        --set secrets.gatewayExistingSecret=TEST-spire-gateway-secrets
}

# --- container env of one deployment, as NAME=VALUE lines ---
env_of() {   # env_of <rendered> <deployment-name>
    printf '%s' "$1" | awk -v want="$2" '
        /^---/                       { kind=""; name="" }
        /^kind:/                     { kind=$2 }
        /^  name:/ && name==""       { name=$2 }
        kind=="Deployment" && name==want { print }
    '
}

assert_absent() {   # assert_absent <rendered> <deployment> <var>
    if env_of "$1" "$2" | grep -q "$3"; then
        fail "$3 must NOT reach $2"
    else
        pass "$3 absent from $2"
    fi
}

assert_present() {  # assert_present <rendered> <deployment> <var>
    if env_of "$1" "$2" | grep -q "$3"; then
        pass "$3 present on $2"
    else
        fail "$3 missing from $2"
    fi
}

check_manifests() {
    local out; out="$(render values-simple.yaml)" || { fail "helm template failed"; return; }

    # 1 + 2: the two keysets never meet. A compromised internet-facing gateway must be able to verify
    # webhook signatures and NOTHING else (ADR-015).
    assert_present "$out" spire-orchestrator SPIRE_ENCRYPTION_KEYSET
    assert_present "$out" spire-worker       SPIRE_ENCRYPTION_KEYSET
    assert_absent  "$out" spire-gateway      SPIRE_ENCRYPTION_KEYSET
    assert_present "$out" spire-gateway      SPIRE_ENCRYPTION_WEBHOOK_KEYSET
    assert_absent  "$out" spire-orchestrator SPIRE_ENCRYPTION_WEBHOOK_KEYSET
    assert_absent  "$out" spire-worker       SPIRE_ENCRYPTION_WEBHOOK_KEYSET

    # 3: the gateway connects as its own schema-scoped role.
    local gw_user orch_user
    gw_user="$(env_of "$out" spire-gateway | grep -A2 QUARKUS_DATASOURCE_USERNAME | grep -o 'key: [^ ]*' | head -1)"
    orch_user="$(env_of "$out" spire-orchestrator | grep -A2 QUARKUS_DATASOURCE_USERNAME | grep -o 'key: [^ ]*' | head -1)"
    if [ -n "$gw_user" ] && [ "$gw_user" != "$orch_user" ]; then
        pass "gateway datasource user differs from the orchestrator's"
    else
        fail "gateway and orchestrator share a datasource user ($gw_user vs $orch_user)"
    fi

    # 4: three distinct OIDC client secrets. One shared secret ends the per-service isolation.
    local secret_keys
    secret_keys="$(printf '%s' "$out" | grep -B1 -A3 SPIRE_OIDC_CLIENT_SECRET | grep -o 'key: [^ ]*' | sort -u | wc -l)"
    if [ "$secret_keys" -ge 3 ]; then
        pass "three distinct OIDC client-secret keys"
    else
        fail "expected 3 distinct OIDC client-secret keys, found $secret_keys"
    fi

    # 5: the dashboard is the only ingress. trusted-proxies is meaningless if a service port is
    # reachable directly — anything that can reach it forges X-Forwarded-For.
    local exposed
    exposed="$(printf '%s' "$out" | awk '/^kind: Service/,/^---/' | grep -c 'type: \(NodePort\|LoadBalancer\)')"
    if [ "$exposed" -le 1 ]; then
        pass "at most one externally-typed Service"
    else
        fail "$exposed Services are externally typed; only the dashboard may be"
    fi
}

check_repo_config() {
    # 6: the nginx template. /webhooks must precede the SPA fallback, both sockets must upgrade,
    # Host must not be rewritten, and X-Forwarded-Proto must not come from $scheme.
    local conf; conf="$(cat "$NGINX")"
    for prefix in '/webhooks' '/api' '/gw' '/wk'; do
        if printf '%s' "$conf" | grep -q "location $prefix"; then
            pass "nginx routes $prefix"
        else
            fail "nginx does not route $prefix"
        fi
    done
    if printf '%s' "$conf" | grep -q 'X-Forwarded-Proto \$scheme'; then
        fail 'nginx sets X-Forwarded-Proto from $scheme, clobbering an upstream TLS terminator'
    else
        pass 'X-Forwarded-Proto passes an upstream value through'
    fi
    if printf '%s' "$conf" | grep -q 'proxy_set_header Host \$host'; then
        pass 'Host is preserved'
    else
        fail 'Host is not preserved — redirect_uri will point at a backend port'
    fi
    local upgrades; upgrades="$(printf '%s' "$conf" | grep -c 'proxy_set_header Upgrade')"
    if [ "$upgrades" -ge 2 ]; then
        pass "WebSocket upgrade on both socket prefixes"
    else
        fail "expected upgrade headers on /api and /gw, found $upgrades"
    fi
    local webhook_line spa_line
    webhook_line="$(grep -n 'location /webhooks' "$NGINX" | cut -d: -f1)"
    spa_line="$(grep -n 'location / {' "$NGINX" | cut -d: -f1)"
    if [ -n "$webhook_line" ] && [ -n "$spa_line" ] && [ "$webhook_line" -lt "$spa_line" ]; then
        pass "/webhooks precedes the SPA fallback"
    else
        fail "/webhooks does not precede the SPA fallback"
    fi

    # 7: three distinct cookie paths, read from the services' own config (baked into the images).
    local paths
    paths="$(grep -h 'cookie-path:' "$REPO"/spire-gateway/src/main/resources/application.yml \
                                    "$REPO"/spire-orchestrator/src/main/resources/application.yml \
                                    "$REPO"/spire-review-worker/src/main/resources/application.yml \
             | awk '{print $2}' | sort -u | wc -l)"
    if [ "$paths" -eq 3 ]; then
        pass "three distinct cookie paths"
    else
        fail "expected 3 distinct cookie paths, found $paths"
    fi
}

self_test() {
    # A negative assertion ("absent where it does not belong") passes trivially when a key is renamed
    # or a grep looks in the wrong place. Six passing greps and six greps that matched nothing are
    # indistinguishable without this.
    echo "== self-test: each check must catch its own break =="
    local broken=0

    # Break: give the gateway the master keyset.
    if render values-simple.yaml --set gateway.giveMasterKeyset=true 2>/dev/null \
        | awk '/^kind: Deployment/,/^---/' | grep -q 'SPIRE_ENCRYPTION_KEYSET'; then
        echo "  ok    the chart CAN render the broken shape (so assertion 1 is not vacuous)"
    else
        echo "  FAIL  cannot render the broken shape — assertion 1 may be untestable"
        broken=1
    fi

    # Break: the nginx template loses /webhooks.
    local tmp; tmp="$(mktemp)"
    grep -v 'location /webhooks' "$NGINX" > "$tmp"
    local saved; saved="$(mktemp)"; cp "$NGINX" "$saved"
    cp "$tmp" "$NGINX"
    if FAILED=0; check_repo_config >/dev/null 2>&1; [ "$FAILED" -ne 0 ]; then
        echo "  ok    removing /webhooks fails the config checks"
    else
        echo "  FAIL  removing /webhooks did NOT fail the config checks"
        broken=1
    fi
    cp "$saved" "$NGINX"; rm -f "$tmp" "$saved"

    # Break: X-Forwarded-Proto from $scheme.
    saved="$(mktemp)"; cp "$NGINX" "$saved"
    printf '\n    proxy_set_header X-Forwarded-Proto $scheme;\n' >> "$NGINX"
    if FAILED=0; check_repo_config >/dev/null 2>&1; [ "$FAILED" -ne 0 ]; then
        echo "  ok    a \$scheme-derived X-Forwarded-Proto fails the config checks"
    else
        echo "  FAIL  a \$scheme-derived X-Forwarded-Proto was NOT caught"
        broken=1
    fi
    cp "$saved" "$NGINX"; rm -f "$saved"

    return "$broken"
}

if [ "${1:-}" = "--self-test" ]; then
    self_test || exit 1
    echo "self-test passed"
    exit 0
fi

echo "== rendered manifests =="
check_manifests
echo "== in-repo config =="
check_repo_config
echo
[ "$FAILED" -eq 0 ] && { echo "all invariants hold"; exit 0; }
echo "INVARIANTS VIOLATED"; exit 1
```

`chmod +x deploy/helm/spire/tests/render.sh`.

Note `gateway.giveMasterKeyset` — a values flag that exists **only** so the self-test can render the
forbidden shape. Document it in `values.yaml` as test-only and never set it in a preset.

- [ ] **Step 2: Run the assertions and watch them fail**

Run: `./deploy/helm/spire/tests/render.sh`
Expected: FAIL — `helm template failed` (no chart yet). The in-repo config checks should already
**pass**, because Tasks 4 and 5 satisfied them. If a config check fails now, fix the nginx template or
`application.yml`, not the script.

- [ ] **Step 3: Write the chart**

`Chart.yaml`: `apiVersion: v2`, `name: spire`, `description: Self-hosted AI code reviewer
(source-available, FSL-1.1-ALv2)`, `type: application`, `version: 0.1.0`, `appVersion: "edge"`.

`values.yaml` — the shape, with **no secret values and no generation**:

```yaml
image:
  registry: ghcr.io/artyomsv
  tag: edge
  pullPolicy: IfNotPresent

# Secret NAMES only. The chart never generates a keyset: SPIRE_ENCRYPTION_KEYSET decrypts rows at
# rest, so a chart-minted one would rotate on the next `helm upgrade` and make every encrypted event
# payload, provider secret and context blob permanently unreadable. Helm's randAlphaNum idiom is safe
# for shared state and unsafe for keys to existing data.
secrets:
  existingSecret: ""          # required: POSTGRES_PASSWORD, SPIRE_ENCRYPTION_KEYSET, the 3 OIDC secrets
  gatewayExistingSecret: ""   # required: GATEWAY_POSTGRES_PASSWORD, SPIRE_ENCRYPTION_WEBHOOK_KEYSET

postgres:
  bundled: true
  host: ""
  database: spire
  user: spire
  gatewayUser: gateway
  storage: 10Gi

kafka:
  bundled: true
  bootstrapServers: ""

oidc:
  bundled: false
  authServerUrl: ""

# The dashboard is the ONLY ingress. Service ports are never exposed, which is what makes
# trustedProxies meaningful.
trustedProxies: ""            # required: the UI's network/pod CIDR

ingress:
  enabled: true
  className: ""
  host: spire.example.invalid
  tls:
    enabled: true             # session cookies in plaintext are replayable
    secretName: spire-tls

gateway:
  # TEST-ONLY. Renders the forbidden shape so tests/render.sh --self-test can prove assertion 1
  # discriminates. Never set this in a preset or a real install.
  giveMasterKeyset: false
```

`values-simple.yaml`: `postgres.bundled: true`, `kafka.bundled: true`, `oidc.bundled: true`,
`ingress.tls.enabled: false` with a comment that this is for localhost evaluation only.

`values-production.yaml`: all three `bundled: false`, `postgres.host` / `kafka.bootstrapServers` /
`oidc.authServerUrl` required, TLS on.

`_helpers.tpl` must include a `required` call per missing secret, e.g.:

```
{{- define "spire.existingSecret" -}}
{{ required "secrets.existingSecret is required: create a Secret holding POSTGRES_PASSWORD, SPIRE_ENCRYPTION_KEYSET, SPIRE_OIDC_ORCHESTRATOR_SECRET, SPIRE_OIDC_GATEWAY_SECRET and SPIRE_OIDC_WORKER_SECRET, then set secrets.existingSecret to its name." .Values.secrets.existingSecret }}
{{- end -}}
```

Each of the three service Deployments carries the **full env set** from Task 7's compose file:
`QUARKUS_PROFILE`, `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME`,
`QUARKUS_DATASOURCE_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `SPIRE_OIDC_AUTH_SERVER_URL`,
`SPIRE_OIDC_CLIENT_ID`, `SPIRE_OIDC_CLIENT_SECRET`, `SPIRE_TRUSTED_PROXIES`, plus the keyset that
service is entitled to and nothing more. Passwords and secrets come via `secretKeyRef`.

`gateway-role-job.yaml` — a `helm.sh/hook: pre-install,pre-upgrade` Job running the same two
statements `infra/postgres-init/01-gateway-role.sh` runs, made idempotent:

```sql
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'gw') THEN
    EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', :'gw', :'pw');
  END IF;
END $$;
CREATE SCHEMA IF NOT EXISTS gateway AUTHORIZATION :"gw";
```

The hook ordering matters: the gateway cannot connect at all until this role exists. This is the real
first-boot constraint — there is no cross-service Flyway race, because the three services own three
schemas with three independent history tables.

`NOTES.txt`: the no-TLS warning when `ingress.tls.enabled=false`, the dashboard URL, and a line
stating the dashboard must stay at the origin root because every redirect target in the services is
`/` and the cookie paths are absolute.

- [ ] **Step 4: Lint and render**

```bash
helm lint deploy/helm/spire -f deploy/helm/spire/values-simple.yaml
helm lint deploy/helm/spire -f deploy/helm/spire/values-production.yaml
./deploy/helm/spire/tests/render.sh
```

Expected: two clean lints and `all invariants hold`.

- [ ] **Step 5: Prove the assertions discriminate**

Run: `./deploy/helm/spire/tests/render.sh --self-test`
Expected: `self-test passed`. If any line reports `FAIL`, the corresponding assertion cannot catch its
own break and is decoration — fix it before moving on.

- [ ] **Step 6: Prove `required` fires**

Run: `helm template spire deploy/helm/spire -f deploy/helm/spire/values-production.yaml`
Expected: FAIL naming `secrets.existingSecret`. A successful render means a secret has a default,
which is the failure mode the whole section exists to prevent.

- [ ] **Step 7: Commit**

```bash
git add deploy/helm/spire
git commit -m "Chart the deployment, and assert what only its shape can hold"
```

---

### Task 10: kustomize, rendered manifests, and `manifests.yml`

**Files:**
- Create: `deploy/kustomize/base/kustomization.yaml`
- Create: `deploy/kustomize/overlays/simple/kustomization.yaml`
- Create: `deploy/kustomize/overlays/production/kustomization.yaml`
- Create: `deploy/render-manifests.sh`
- Create: `deploy/k8s/simple/spire.yaml`, `deploy/k8s/production/spire.yaml` (generated)
- Create: `.github/workflows/manifests.yml`

**Interfaces:**
- Consumes: the chart (Task 9).
- Produces: `render-manifests.sh [--check]`, exiting non-zero when committed YAML differs from a fresh render.

- [ ] **Step 1: Write the kustomize bases**

`deploy/kustomize/base/kustomization.yaml`:

```yaml
# Inflates the chart rather than duplicating it: the chart is the single source of truth, so a
# kustomize user and a Helm user cannot receive different manifests.
#   kustomize build --enable-helm deploy/kustomize/overlays/simple
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

helmCharts:
  - name: spire
    releaseName: spire
    version: 0.1.0
    repo: ""
    valuesFile: ../../helm/spire/values.yaml
```

Each overlay sets `helmCharts[0].valuesFile` to its preset's values file and adds a `nameSuffix` or
namespace as appropriate. Keep them minimal — an overlay that re-states chart content is drift waiting
to happen.

- [ ] **Step 2: Write the generator**

`deploy/render-manifests.sh`:

```bash
#!/usr/bin/env bash
# Renders the chart to plain YAML so `kubectl apply -f` users get the SAME manifests as Helm and
# kustomize users. --check turns divergence from a discipline problem into a build failure.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

for preset in simple production; do
    out="$HERE/k8s/$preset/spire.yaml"
    mkdir -p "$(dirname "$out")"
    rendered="$(helm template spire "$HERE/helm/spire" \
        -f "$HERE/helm/spire/values-$preset.yaml" \
        --set secrets.existingSecret=spire-secrets \
        --set secrets.gatewayExistingSecret=spire-gateway-secrets \
        --set trustedProxies=10.0.0.0/8 \
        --set postgres.host=postgres.example.invalid \
        --set kafka.bootstrapServers=kafka.example.invalid:9092 \
        --set oidc.authServerUrl=https://idp.example.invalid/realms/spire)"
    if [ "$CHECK" -eq 1 ]; then
        if ! printf '%s\n' "$rendered" | diff -u "$out" - > /dev/null; then
            echo "DRIFT: $out is not what the chart renders. Run ./deploy/render-manifests.sh and commit."
            printf '%s\n' "$rendered" | diff -u "$out" - || true
            exit 1
        fi
        echo "  ok  $out matches the chart"
    else
        printf '%s\n' "$rendered" > "$out"
        echo "  wrote $out"
    fi
done
```

`chmod +x deploy/render-manifests.sh`. The `example.invalid` placeholders keep the committed
manifests obviously non-real.

- [ ] **Step 3: Generate, then prove the check catches drift**

```bash
./deploy/render-manifests.sh
./deploy/render-manifests.sh --check          # expect: ok, ok
echo "# drift" >> deploy/k8s/simple/spire.yaml
./deploy/render-manifests.sh --check          # expect: DRIFT, exit 1
./deploy/render-manifests.sh                  # regenerate
./deploy/render-manifests.sh --check          # expect: ok, ok
```

- [ ] **Step 4: Validate with kubeconform and kustomize**

```bash
kustomize build --enable-helm deploy/kustomize/overlays/simple > /tmp/simple.yaml
kustomize build --enable-helm deploy/kustomize/overlays/production > /tmp/production.yaml
kubeconform -strict -summary /tmp/simple.yaml /tmp/production.yaml
```

Expected: both build, kubeconform reports no invalid resources.

- [ ] **Step 5: Write the workflow**

`.github/workflows/manifests.yml`:

```yaml
# Everything under deploy/ gets a real check. The drift gate is the load-bearing one: Helm,
# kustomize and kubectl-apply users must receive the same manifests, and only a build failure
# reliably keeps that true.
name: manifests

on:
  pull_request:
    paths: ['deploy/**', 'spire-ui/nginx/**', '.github/workflows/manifests.yml']
  push:
    branches: [master]
    paths: ['deploy/**', 'spire-ui/nginx/**']

permissions:
  contents: read

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - name: Install kustomize and kubeconform
        run: |
          curl -sSL "https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh" | bash
          sudo mv kustomize /usr/local/bin/
          curl -sSL "https://github.com/yannh/kubeconform/releases/latest/download/kubeconform-linux-amd64.tar.gz" | tar xz kubeconform
          sudo mv kubeconform /usr/local/bin/

      - run: helm lint deploy/helm/spire -f deploy/helm/spire/values-simple.yaml
      - run: helm lint deploy/helm/spire -f deploy/helm/spire/values-production.yaml
      - run: ./deploy/helm/spire/tests/render.sh
      - run: ./deploy/helm/spire/tests/render.sh --self-test
      - run: ./deploy/render-manifests.sh --check

      - name: kustomize builds
        run: |
          kustomize build --enable-helm deploy/kustomize/overlays/simple > /tmp/simple.yaml
          kustomize build --enable-helm deploy/kustomize/overlays/production > /tmp/production.yaml
      - name: Schemas
        run: kubeconform -strict -summary -schema-location default -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' /tmp/simple.yaml /tmp/production.yaml

  kind-install:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - uses: helm/kind-action@v1
      - name: Install the simple preset
        run: |
          kubectl create secret generic spire-secrets \
            --from-literal=POSTGRES_PASSWORD=TEST-postgres-password \
            --from-literal=SPIRE_ENCRYPTION_KEYSET=TEST-master-keyset \
            --from-literal=SPIRE_OIDC_ORCHESTRATOR_SECRET=dev-only-orchestrator-secret \
            --from-literal=SPIRE_OIDC_GATEWAY_SECRET=dev-only-gateway-secret \
            --from-literal=SPIRE_OIDC_WORKER_SECRET=dev-only-worker-secret
          kubectl create secret generic spire-gateway-secrets \
            --from-literal=GATEWAY_POSTGRES_PASSWORD=TEST-gateway-password \
            --from-literal=SPIRE_ENCRYPTION_WEBHOOK_KEYSET=TEST-webhook-keyset
          helm install spire deploy/helm/spire -f deploy/helm/spire/values-simple.yaml \
            --set secrets.existingSecret=spire-secrets \
            --set secrets.gatewayExistingSecret=spire-gateway-secrets \
            --set trustedProxies=10.244.0.0/16 \
            --wait --timeout 10m
      - if: failure()
        run: kubectl get pods -o wide && kubectl describe pods && kubectl logs -l app.kubernetes.io/name=spire --tail 100
```

The kind job proves the manifests **apply and the pods reach ready** — including that the
gateway-role Job runs before the gateway Deployment. The `TEST-` keysets are not valid Tink keysets;
if the services refuse to start on them, generate real ones in the step rather than loosening the
check.

- [ ] **Step 6: Commit**

```bash
git add deploy/kustomize deploy/render-manifests.sh deploy/k8s .github/workflows/manifests.yml
git commit -m "Derive kustomize and plain manifests from the chart, and fail on drift"
```

---

### Task 11: `release.yml`

**Files:**
- Create: `.github/workflows/release.yml`

**Interfaces:**
- Consumes: everything above, notably `deploy/e2e.sh` (Task 8).
- Produces: signed multi-arch images tagged from the git tag, plus a GitHub Release.

- [ ] **Step 1: Write the workflow**

`.github/workflows/release.yml`:

```yaml
# Cut on a v* tag. Multi-arch, signed, SBOM'd, and gated on the same e2e script that runs nightly —
# a release must not publish a topology that has not been exercised.
name: release

on:
  push:
    tags: ['v*']

permissions:
  contents: write      # the GitHub Release
  packages: write      # GHCR
  id-token: write      # cosign keyless

jobs:
  gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-buildx-action@v3
      - name: Build amd64 images locally
        run: |
          for svc in gateway orchestrator review-worker; do
            docker build --build-arg SERVICE=$svc -t ghcr.io/artyomsv/spire-$svc:edge .
          done
          docker build -t ghcr.io/artyomsv/spire-ui:edge ./spire-ui
      - name: Environment
        run: |
          cat > deploy/.env <<'ENV'
          SPIRE_UI_PORT=34700
          SPIRE_KEYCLOAK_PORT=34767
          POSTGRES_DB=spire
          POSTGRES_USER=spire
          POSTGRES_PASSWORD=TEST-postgres-password
          GATEWAY_POSTGRES_USER=gateway
          GATEWAY_POSTGRES_PASSWORD=TEST-gateway-password
          SPIRE_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/spire
          SPIRE_OIDC_ORCHESTRATOR_SECRET=dev-only-orchestrator-secret
          SPIRE_OIDC_GATEWAY_SECRET=dev-only-gateway-secret
          SPIRE_OIDC_WORKER_SECRET=dev-only-worker-secret
          KEYCLOAK_ADMIN_USER=dev-admin
          KEYCLOAK_ADMIN_PASSWORD=TEST-keycloak-admin
          SPIRE_TRUSTED_PROXIES=172.16.0.0/12
          SPIRE_ENCRYPTION_KEYSET=REPLACE_AT_RUNTIME
          SPIRE_ENCRYPTION_WEBHOOK_KEYSET=REPLACE_AT_RUNTIME
          ENV
      - run: docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d
      - name: Wait
        run: for i in $(seq 1 60); do curl -fsS -o /dev/null http://localhost:34700/api/me && exit 0; sleep 5; done; exit 1
      - name: Round-trip
        env:
          DEV_VIEWER_PASSWORD: dev-viewer-password
        run: |
          set -a; . deploy/.env; set +a
          ./deploy/e2e.sh http://localhost:34700 http://localhost:34767
      - if: always()
        run: docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env down -v

  publish:
    needs: gate
    runs-on: ubuntu-latest
    strategy:
      matrix:
        include:
          - name: spire-gateway
            context: .
            dockerfile: Dockerfile
            service: gateway
          - name: spire-orchestrator
            context: .
            dockerfile: Dockerfile
            service: orchestrator
          - name: spire-review-worker
            context: .
            dockerfile: Dockerfile
            service: review-worker
          - name: spire-ui
            context: ./spire-ui
            dockerfile: ./spire-ui/Dockerfile
            service: ""
    steps:
      - uses: actions/checkout@v4
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - uses: sigstore/cosign-installer@v3

      - name: Version from the tag
        run: echo "VERSION=${GITHUB_REF_NAME#v}" >> "$GITHUB_ENV"

      # arm64 goes through QEMU and is slow (§5.4) — which is exactly why this is tag-triggered.
      - name: Build and push
        id: push
        uses: docker/build-push-action@v6
        with:
          context: ${{ matrix.context }}
          file: ${{ matrix.dockerfile }}
          build-args: ${{ matrix.service && format('SERVICE={0}', matrix.service) || '' }}
          platforms: linux/amd64,linux/arm64
          push: true
          provenance: true
          sbom: true
          tags: |
            ghcr.io/artyomsv/${{ matrix.name }}:${{ env.VERSION }}
            ghcr.io/artyomsv/${{ matrix.name }}:latest
          cache-from: type=gha,scope=${{ matrix.name }}

      - name: Sign
        run: cosign sign --yes ghcr.io/artyomsv/${{ matrix.name }}@${{ steps.push.outputs.digest }}

  github-release:
    needs: publish
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
      - name: Package the chart at this version
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          helm package deploy/helm/spire --version "$VERSION" --app-version "$VERSION" -d dist/
      # Auto-generated notes: the commit discipline here already reads as release notes, and a
      # CHANGELOG file is one more thing that can go stale or fail a release by omission.
      - uses: softprops/action-gh-release@v2
        with:
          generate_release_notes: true
          files: dist/*.tgz
```

- [ ] **Step 2: Validate the YAML and job graph**

Run: `docker run --rm -v "$(git rev-parse --show-toplevel):/w" -w //w mikefarah/yq:4 e '.jobs | to_entries | .[] | .key + " needs: " + (.value.needs // "none" | tostring)' .github/workflows/release.yml`
Expected: `gate needs: none`, `publish needs: gate`, `github-release needs: publish`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "Release on a tag, gated on the topology check"
```

---

### Task 12: Scanning workflows and Dependabot

**Files:**
- Create: `.github/workflows/codeql.yml`, `.github/workflows/secret-scan.yml`, `.github/workflows/semgrep.yml`, `.github/workflows/dependabot-auto-merge.yml`
- Create: `.github/dependabot.yml`, `.gitleaksignore`

**Interfaces:**
- Consumes: `ci` (Task 2) as the auto-merge gate.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: `codeql.yml`**

```yaml
name: codeql

on:
  pull_request:
  push:
    branches: [master]
  schedule:
    - cron: '41 4 * * 1'

permissions:
  contents: read
  security-events: write

jobs:
  analyze:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        include:
          - language: java-kotlin
            build-mode: manual
          - language: javascript-typescript
            build-mode: none
    steps:
      - uses: actions/checkout@v4
      - if: matrix.language == 'java-kotlin'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: github/codeql-action/init@v3
        with:
          languages: ${{ matrix.language }}
          build-mode: ${{ matrix.build-mode }}
      - if: matrix.build-mode == 'manual'
        run: ./gradlew assemble --no-daemon
      - uses: github/codeql-action/analyze@v3
        with:
          category: /language:${{ matrix.language }}
```

- [ ] **Step 2: `secret-scan.yml` and `.gitleaksignore`**

```yaml
name: secret-scan

on:
  pull_request:
  push:
    branches: [master]

permissions:
  contents: read

jobs:
  gitleaks:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - uses: gitleaks/gitleaks-action@v2
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

`.gitleaksignore` — the three realm client secrets are obviously-synthetic dev placeholders, and the
compose defaults reference the same strings:

```
# Dev-only OIDC client secrets in the importable Keycloak realms. Obviously synthetic
# ("dev-only-*-secret"), overridden by every deployment, and required in the file for the realm to
# import at all.
infra/keycloak/realm-spire.json:generic-api-key
deploy/keycloak/realm-spire.json:generic-api-key
docker-compose.auth.yml:generic-api-key
```

Run gitleaks locally first to get the exact rule ids and fingerprints, and use those:

```bash
docker run --rm -v "$(git rev-parse --show-toplevel):/repo" zricethezav/gitleaks:latest detect --source=/repo --no-banner -v
```

If it reports findings other than those three files, **stop and investigate** — that is a real leak.

- [ ] **Step 3: `semgrep.yml`**

```yaml
# Blocking, not report-only: this repository is already Semgrep-clean, so this records existing
# discipline rather than raising the bar.
name: semgrep

on:
  pull_request:
  push:
    branches: [master]

permissions:
  contents: read

jobs:
  scan:
    runs-on: ubuntu-latest
    container:
      image: semgrep/semgrep
    steps:
      - uses: actions/checkout@v4
      - run: semgrep scan --config=auto --error --skip-unknown-extensions
```

Run it locally first: `docker run --rm -v "$(git rev-parse --show-toplevel):/src" semgrep/semgrep
semgrep scan --config=auto --error`. If it is not clean, drop `--error` and file the findings as tech
debt rather than blocking every PR on a pre-existing baseline.

- [ ] **Step 4: `.github/dependabot.yml`**

```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /
    schedule: {interval: weekly}
    open-pull-requests-limit: 5
  - package-ecosystem: npm
    directory: /spire-ui
    schedule: {interval: weekly}
    open-pull-requests-limit: 5
  - package-ecosystem: github-actions
    directory: /
    schedule: {interval: weekly}
  - package-ecosystem: docker
    directory: /
    schedule: {interval: weekly}
  - package-ecosystem: docker
    directory: /spire-ui
    schedule: {interval: weekly}
```

- [ ] **Step 5: `dependabot-auto-merge.yml`**

```yaml
# Narrow on purpose: action pins and patch-level npm bumps only. A minor Quarkus or React bump is a
# decision, not maintenance.
name: dependabot-auto-merge

on: pull_request_target

permissions:
  contents: write
  pull-requests: write

jobs:
  merge:
    if: github.actor == 'dependabot[bot]'
    runs-on: ubuntu-latest
    steps:
      - uses: dependabot/fetch-metadata@v2
        id: meta
        with:
          github-token: ${{ secrets.GITHUB_TOKEN }}
      - if: |
          steps.meta.outputs.package-ecosystem == 'github_actions' ||
          (steps.meta.outputs.package-ecosystem == 'npm_and_yarn' &&
           steps.meta.outputs.update-type == 'version-update:semver-patch')
        run: gh pr merge --auto --squash "$PR_URL"
        env:
          PR_URL: ${{ github.event.pull_request.html_url }}
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

- [ ] **Step 6: Validate every workflow parses**

```bash
for f in .github/workflows/*.yml .github/dependabot.yml; do
  docker run --rm -v "$(git rev-parse --show-toplevel):/w" -w //w mikefarah/yq:4 e 'true' "$f" > /dev/null \
    && echo "ok   $f" || echo "BAD  $f"
done
```

Expected: `ok` for all ten files.

- [ ] **Step 7: Commit**

```bash
git add .github/workflows/codeql.yml .github/workflows/secret-scan.yml \
        .github/workflows/semgrep.yml .github/workflows/dependabot-auto-merge.yml \
        .github/dependabot.yml .gitleaksignore
git commit -m "Scan code, secrets and dependencies on every change"
```

---

### Task 13: Documentation

**Files:**
- Modify: `README.md`, `docs/CICD-AND-PACKAGING.md`, `docs/ROADMAP.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: everything.
- Produces: nothing.

- [ ] **Step 1: `README.md`**

Add a **Deployment** section: the one-command install, a pointer to `deploy/README.md`, the two
presets, and the no-TLS warning stated plainly. Do not describe the project as open source.

- [ ] **Step 2: `docs/CICD-AND-PACKAGING.md`**

Change the status line from *parked, not started* to delivered with today's date. Replace §7's open
decisions with the answers (scope, proxy topology, release trigger, presets, IdP, `docker.yml`
pushing). Rewrite §1's "starting point" table to describe what now exists. Keep §6 as a record of why
the order was what it was, and note that D10 turned out to *add* scope rather than merely gate it.
Correct §3's "static-serving image for the UI" — it is a reverse proxy, and that is a security
control.

- [ ] **Step 3: `docs/ROADMAP.md`**

Move the CI/CD row out of *What is actually left* into *Delivered*, with a summary naming the four
defects the spec review caught. Update the delivered-count line if one exists.

- [ ] **Step 4: `CLAUDE.md`**

Add a Status bullet for this work in the established voice: what landed, and the two non-obvious
facts a future reader needs — that the UI image's nginx routing is the mechanism ADR-022 depends on
(so `/webhooks` falling through to the SPA silently loses every review), and that the chart must
never generate the Tink keysets. Update the Build & run section with the packaged commands.

- [ ] **Step 5: Verify the docs agree with the code**

```bash
grep -rn "open source" README.md docs/ deploy/ || echo "clean"
grep -rn "parked" docs/CICD-AND-PACKAGING.md || echo "status updated"
```

Expected: `clean` and `status updated`.

- [ ] **Step 6: Full verification before the final commit**

```bash
./gradlew build --no-daemon
cd spire-ui && npx tsc --noEmit && npm run test && cd ..
./deploy/helm/spire/tests/render.sh
./deploy/helm/spire/tests/render.sh --self-test
./deploy/render-manifests.sh --check
```

Expected: all green. Report the Java and vitest totals in the commit body.

- [ ] **Step 7: Commit**

```bash
git add README.md docs/CICD-AND-PACKAGING.md docs/ROADMAP.md CLAUDE.md
git commit -m "Record the packaging work and what it changed"
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: test tiers + guard → 1; workflows → 2, 6, 8, 10, 11, 12; service images incl. `QUARKUS_HTTP_PORT`, the CRLF fix, four-layer fast-jar, OCI labels → 3; nginx incl. `/webhooks`, `X-Forwarded-Proto` map, WS mechanics → 4; the three prod properties incl. `trusted-proxies` → 5; config contract + per-service env wiring + bundled IdP → 7; the e2e round-trip incl. the webhook line and the privilege probe → 8; chart + no-secret-generation + `required` + gateway-role Job + all seven assertions + `--self-test` → 9; kustomize + rendered + drift → 10; docs → 13.

**Placeholders.** None. Two steps deliberately depend on a local observation before being finalised — the gitleaks rule ids (Task 12 Step 2) and whether a Tink keyset generator task exists (Task 8 Step 5) — and both state the command to run and what to do with each outcome, including when to stop.

**Type consistency.** `fastTestModules` / `serviceTestModules` are named identically in Task 1's build code and its guard's parser. `testFast` / `testServices` match between Task 1 and Task 2. `SPIRE_ORCHESTRATOR_URL` / `SPIRE_GATEWAY_URL` / `SPIRE_WORKER_URL` match between Task 4's template, its Dockerfile defaults and Task 7's compose. `SPIRE_TRUSTED_PROXIES` matches between Task 5, Task 7 and Task 9's `values.yaml` (`trustedProxies`). `secrets.existingSecret` / `secrets.gatewayExistingSecret` match between Task 9's values, its helper, Task 9's `render.sh`, Task 10's generator and Task 10's kind job. `deploy/e2e.sh`'s signature is identical in Tasks 8 and 11. The four image names are identical across Tasks 6, 7, 10 and 11.
