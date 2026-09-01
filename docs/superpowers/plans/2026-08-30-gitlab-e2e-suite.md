# GitLab End-to-End Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automate the manual Mode G parity runbook against a containerised GitLab CE, so the review loop is verified against a real SCM instead of against our beliefs about one.

**Architecture:** A compose overlay adds two services to the packaged stack — a real `gitlab/gitlab-ce` and a WireMock speaking the OpenAI-compatible wire. A new Gradle module `spire-e2e` drives the whole thing from outside over HTTP and a `psql` subprocess, never touching our code's internals. Four merge requests carry the scenarios: one long ordered chain, two per-language code-context probes, one rename.

**Tech Stack:** Java 25, JUnit 5 (`org.junit:junit-bom:6.1.3`), WireMock 3.13.2, JDK `HttpClient`, Jackson 2.22.1, Docker Compose, GitLab CE, Keycloak.

**Spec:** `docs/superpowers/specs/2026-08-29-gitlab-e2e-suite-design.md` — read it before Task 1. This plan argues from it and does not restate its reasoning.

## Global Constraints

- **Java 25** toolchain on every module (`JavaLanguageVersion.of(25)`).
- **4-space indentation** for Java. **2-space** for YAML, JSON and Kotlin build scripts.
- **Explicit types over `var`** in Java. **`interface` over `type`** for object shapes in TypeScript.
- **No fabricated data.** Every fixture value must be self-labelling: user names `e2e-bot` / `e2e-human`, project names prefixed `e2e-`, defect markers `E2E-DEFECT-*`, model name `e2e-mock-model`. No realistic-looking prices, no plausible commit shas, no memory-recalled values. The model is catalogued `UNMETERED`, never with an invented rate.
- **Never mention AI authoring** in commit messages. Imperative mood, first line ≤72 chars.
- **`spire-e2e` may depend on no other module in this repo.** It drives the stack the way an operator would. This is a licensing invariant (`LICENSING.md`) and an honesty invariant.
- **Naming rule for transport types:** `*Dto` is the default; `*View` only for read-only projections; `*Payload` only for RabbitMQ envelope inners. This module introduces no wire types — its records are local to the harness and take plain descriptive names.
- **Known deviation, must be stated in code comments where it is set:** the suite runs with `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS=true`, which disables the SSRF guard's production behaviour. `ProviderUrlValidationTest` in `testServices` remains its only coverage.
- **Nothing in `deploy/compose.yml` may be edited.** The overlay adds; it does not modify.
- Docker Compose project name is `spire`; the packaged stack's UI service is named `ui` and listens on container port `8080`.

---

## File Structure

| File | Responsibility |
|---|---|
| `build.gradle.kts` (modify) | Declares the third CI tier `e2eTestModules` and the `testE2e` task |
| `settings.gradle.kts` (modify) | Includes `spire-e2e` |
| `spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java` (modify) | Guard extended from two tiers to three |
| `spire-e2e/build.gradle.kts` | Module build; JUnit + WireMock client + Jackson; no repo dependencies |
| `spire-e2e/LICENSE` | FSL-1.1-ALv2, matching the deployables it exercises |
| `spire-e2e/src/test/java/dev/codespire/e2e/support/Stack.java` | Where the stack is, and the fail-fast health precondition |
| `spire-e2e/src/test/java/dev/codespire/e2e/support/Await.java` | The async contract: poll-to-state, and quiet-period absence |
| `spire-e2e/src/test/java/dev/codespire/e2e/support/Psql.java` | Read the read model through `docker compose exec` |
| `spire-e2e/src/test/java/dev/codespire/e2e/support/Json.java` | Tiny Jackson wrapper shared by both drivers |
| `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/GitLabDriver.java` | Drives GitLab as a human would: users, projects, commits, MRs, notes |
| `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/Rails.java` | `gitlab-rails runner` calls — the only way to mint a known PAT |
| `spire-e2e/src/test/java/dev/codespire/e2e/spire/SpireDriver.java` | Our REST surface: Keycloak token, provider/model/webhook registration |
| `spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMock.java` | Reads WireMock's request journal — the prompt observer |
| `spire-e2e/src/test/java/dev/codespire/e2e/fixtures/` | The four fixture repositories as resources |
| `spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java` | MR 1: S1–S11, ordered |
| `spire-e2e/src/test/java/dev/codespire/e2e/CodeContextProbeTest.java` | MRs 2 and 3: per-language context probes |
| `spire-e2e/src/test/java/dev/codespire/e2e/RenameTest.java` | MR 4: the rename |
| `spire-e2e/src/test/resources/llm-mock/` | WireMock stub mappings, mounted into the container |
| `deploy/compose.e2e.yml` | The overlay: `gitlab` and `llm-mock` |
| `deploy/e2e-diagnostics.sh` | Log capture on failure |
| `.github/workflows/e2e.yml` (modify) | Second nightly job |

**Milestone:** Task 7 is the walking skeleton. If S1 goes green, every hard integration question — GitLab boot, webhook delivery through nginx, the SSRF relaxation, auth, the mock's wire format — is answered. Tasks 8 onward are additive.

---

### Task 1: The third CI tier

`TestTierCoverageTest` requires every module with tests to be in **exactly one of two** lists, and asserts the service tier has exactly three entries. A third tier is a change to that guard, not an addition to a list.

**Files:**
- Modify: `build.gradle.kts:143-176`
- Modify: `spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a Gradle task `testE2e`, and a `val e2eTestModules = listOf("spire-e2e")` declaration in the root build that `TestTierCoverageTest` parses.

- [ ] **Step 1: Write the failing test**

In `TestTierCoverageTest.java`, replace the two-tier constants and the three assertions that assume two tiers.

```java
    private static final String FAST_TIER = "fastTestModules";

    private static final String SERVICE_TIER = "serviceTestModules";

    private static final String E2E_TIER = "e2eTestModules";

    private static final List<String> ALL_TIERS = List.of(FAST_TIER, SERVICE_TIER, E2E_TIER);
```

Replace `everyModuleWithTestsIsInExactlyOneTier` with a version that counts memberships across all three:

```java
    @Test
    void everyModuleWithTestsIsInExactlyOneTier() throws IOException {
        List<String> unassigned = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (String module : includedModules()) {
            if (!hasTests(module)) {
                continue;
            }
            long tiers = 0;
            for (String tier : ALL_TIERS) {
                if (tierList(tier).contains(module)) {
                    tiers++;
                }
            }
            if (tiers > 1) {
                duplicated.add(module);
            } else if (tiers == 0) {
                unassigned.add(module);
            }
        }

        assertTrue(unassigned.isEmpty(),
                "These modules have tests but belong to no CI tier, so CI never runs them: " + unassigned
                        + ". Add each to one of " + ALL_TIERS + " in the root build.gradle.kts.");
        assertTrue(duplicated.isEmpty(),
                "These modules are in more than one CI tier, so their tests run twice per build: " + duplicated);
    }
```

Replace `neitherTierNamesAModuleThatIsNotIncluded`'s loop source with `ALL_TIERS`:

```java
    @Test
    void noTierNamesAModuleThatIsNotIncluded() throws IOException {
        Set<String> included = includedModules();
        for (String tier : ALL_TIERS) {
            for (String module : tierList(tier)) {
                assertTrue(included.contains(module),
                        tier + " names '" + module + "', which settings.gradle.kts does not include. A stale "
                                + "entry makes the tier task fail to resolve, or silently drops a renamed module.");
            }
        }
    }
```

Extend the vacuity guard. The existing `assertEquals(3, ...)` on the service tier stays — it pins the three deployables — and the e2e tier gets its own:

```java
    @Test
    void theDeclarationsWereActuallyFound() throws IOException {
        assertFalse(tierList(FAST_TIER).isEmpty(), FAST_TIER + " parsed to nothing");
        assertEquals(3, tierList(SERVICE_TIER).size(),
                SERVICE_TIER + " should name exactly the three deployables");
        assertFalse(tierList(E2E_TIER).isEmpty(), E2E_TIER + " parsed to nothing");
        assertTrue(includedModules().size() > 10,
                "settings.gradle.kts parsed to " + includedModules().size() + " modules, which is too few");
    }
```

Update the class javadoc's "CI runs two Gradle lifecycle tasks" sentence to name three, and say what the third is for: modules whose tests need a running packaged stack, which CI runs nightly rather than on the PR path.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`
Expected: FAIL — `theDeclarationsWereActuallyFound` reports `no \`val e2eTestModules = listOf(...)\` in the root build.gradle.kts`.

- [ ] **Step 3: Declare the tier and the task**

In `build.gradle.kts`, after the `serviceTestModules` declaration:

```kotlin
/**
 * Modules whose tests drive a RUNNING packaged stack (deploy/compose.yml plus deploy/compose.e2e.yml)
 * rather than booting their own dependencies. They start nothing: a stack that is not up is a fast,
 * loud failure, not a five-minute wait. CI runs this tier nightly, never on the PR path — see
 * .github/workflows/e2e.yml and docs/superpowers/specs/2026-08-29-gitlab-e2e-suite-design.md.
 */
val e2eTestModules = listOf(
    "spire-e2e",
)
```

And after `testServices`:

```kotlin
tasks.register("testE2e") {
    group = "verification"
    description = "Drives a running packaged stack + containerised GitLab. Nightly; needs the stack up."
    dependsOn(e2eTestModules.map { ":$it:test" })
}
```

Also extend the comment block above `fastTestModules` — it currently says "The two CI test tiers" — to say three, and to name what separates the third: not whether the tests need Docker, but whether they *own* what they talk to.

- [ ] **Step 4: Run the test to verify it still fails, for the right reason**

Run: `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`
Expected: FAIL — now `noTierNamesAModuleThatIsNotIncluded` reports `e2eTestModules names 'spire-e2e', which settings.gradle.kts does not include`. This is the correct next failure: the tier exists, the module does not yet. Task 2 closes it.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts spire-arch/src/test/java/dev/codespire/arch/TestTierCoverageTest.java
git commit -m "Add a third CI test tier for stack-driving tests"
```

---

### Task 2: The `spire-e2e` module skeleton and its health precondition

**Files:**
- Modify: `settings.gradle.kts`
- Create: `spire-e2e/build.gradle.kts`
- Create: `spire-e2e/LICENSE`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/Stack.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/support/StackHealthTest.java`

**Interfaces:**
- Consumes: Task 1's `e2eTestModules` / `testE2e`.
- Produces:
  - `Stack.uiBaseUrl()` → `String`, default `http://localhost:34700`, overridable by `SPIRE_E2E_BASE_URL`.
  - `Stack.keycloakBaseUrl()` → `String`, default `http://localhost:34767`, overridable by `SPIRE_E2E_KEYCLOAK_URL`.
  - `Stack.gitlabBaseUrl()` → `String`, default `http://localhost:34780`, overridable by `SPIRE_E2E_GITLAB_URL`.
  - `Stack.llmMockAdminUrl()` → `String`, default `http://localhost:34781/__admin`, overridable by `SPIRE_E2E_LLM_MOCK_URL`.
  - `Stack.requireUp()` → `void`, throws `IllegalStateException` naming the missing service and the exact command to start it.
  - `Stack.http()` → a shared `HttpClient` with a 30-second connect timeout and `Redirect.NEVER`.

- [ ] **Step 1: Include the module**

In `settings.gradle.kts`, after `include("spire-orchestrator")`:

```kotlin
include("spire-e2e")
```

- [ ] **Step 2: Write the module build file**

Create `spire-e2e/build.gradle.kts`:

```kotlin
// Drives a RUNNING packaged stack from outside: HTTP to the dashboard's nginx, HTTP to a
// containerised GitLab, and `docker compose exec postgres psql` for the read model.
//
// Depends on NO module in this repo, deliberately. Two reasons, and both matter:
// LICENSING.md forbids an Apache-2.0 module depending on a service module, and a harness that can
// import our types can assert things no operator could observe — which is exactly the self-confirming
// test this suite exists to replace.
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
}

tasks.test {
    useJUnitPlatform()

    // A scenario waits on real webhook delivery and two model calls per round. The default 0 (no
    // timeout) would hang a nightly job forever on a wedged stack; Await's own per-step deadlines are
    // the real bound and are much tighter.
    timeout = java.time.Duration.ofMinutes(45)

    // Never cache a pass. The inputs to these tests are a running stack and a live GitLab, neither of
    // which Gradle can see, so an up-to-date check here would report a cached green against a stack
    // that has since changed.
    outputs.upToDateWhen { false }

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
```

- [ ] **Step 3: Assign the licence**

Copy the FSL-1.1-ALv2 text from an existing deployable so the module carries its own licence, as `LICENSING.md` requires:

```bash
cp spire-orchestrator/LICENSE spire-e2e/LICENSE
```

Then add `spire-e2e` to the FSL-1.1-ALv2 table in `LICENSING.md`, with the reason: it drives the deployables and ships no reusable library surface.

- [ ] **Step 4: Write the failing health test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/StackHealthTest.java`:

```java
package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The precondition every other test in this module assumes. It runs first so a stack that is not up
 * produces one clear failure naming the command to fix it, rather than a scenario failing forty
 * seconds later with a connection refused nobody reads.
 */
class StackHealthTest {

    @Test
    void theStackIsUp() {
        assertDoesNotThrow(Stack::requireUp);
    }
}
```

- [ ] **Step 5: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test`
Expected: FAIL to compile — `Stack` does not exist.

- [ ] **Step 6: Write `Stack`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/Stack.java`:

```java
package dev.codespire.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the running stack is, and whether it is actually there.
 *
 * <p>This module starts nothing. GitLab CE takes around five minutes to boot, so a harness that owned
 * the lifecycle would charge that to every local iteration; the stack is brought up once by hand or by
 * CI and re-run against many times. The cost of that choice is exactly this class: the failure when it
 * is NOT up has to be unmistakable, or the first symptom is a scenario timing out for the wrong reason.
 */
public final class Stack {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String START_COMMAND =
            "docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml "
                    + "--env-file deploy/.env up -d --build";

    private Stack() {
    }

    public static HttpClient http() {
        return HTTP;
    }

    public static String uiBaseUrl() {
        return env("SPIRE_E2E_BASE_URL", "http://localhost:34700");
    }

    public static String keycloakBaseUrl() {
        return env("SPIRE_E2E_KEYCLOAK_URL", "http://localhost:34767");
    }

    public static String gitlabBaseUrl() {
        return env("SPIRE_E2E_GITLAB_URL", "http://localhost:34780");
    }

    public static String llmMockAdminUrl() {
        return env("SPIRE_E2E_LLM_MOCK_URL", "http://localhost:34781") + "/__admin";
    }

    /**
     * Probes each service's cheapest public endpoint. Names every one that is down, not just the
     * first: a half-started stack is the common case, and reporting one at a time turns one fix into
     * four runs.
     */
    public static void requireUp() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put("dashboard (deploy/compose.yml, service 'ui')", uiBaseUrl() + "/healthz");
        probes.put("orchestrator API", uiBaseUrl() + "/api/me");
        probes.put("keycloak", keycloakBaseUrl() + "/realms/spire/.well-known/openid-configuration");
        probes.put("gitlab (deploy/compose.e2e.yml)", gitlabBaseUrl() + "/-/readiness");
        probes.put("llm-mock (deploy/compose.e2e.yml)", llmMockAdminUrl() + "/mappings");

        StringBuilder down = new StringBuilder();
        for (Map.Entry<String, String> probe : probes.entrySet()) {
            String failure = check(probe.getValue());
            if (failure != null) {
                down.append("\n  - ").append(probe.getKey()).append(" — ").append(failure);
            }
        }
        if (!down.isEmpty()) {
            throw new IllegalStateException("The e2e stack is not up." + down
                    + "\n\nStart it with:\n  " + START_COMMAND
                    + "\n\nGitLab takes around five minutes to become ready after that returns.");
        }
    }

    /** @return null when the endpoint answered, else a short description of what went wrong. */
    private static String check(String url) {
        try {
            HttpResponse<Void> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500 ? null : "HTTP " + response.statusCode() + " from " + url;
        } catch (IOException e) {
            return "unreachable at " + url + " (" + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted probing " + url, e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
```

Note `/api/me` is deliberately chosen: ADR-022 makes it one of three explicitly public paths, so it proves the orchestrator is answering *through nginx* without needing a token.

- [ ] **Step 7: Run it to verify it fails with the intended message**

Run: `./gradlew :spire-e2e:test`
Expected: FAIL with `The e2e stack is not up.` listing gitlab and llm-mock (the packaged stack may be up from other work; the overlay's two services cannot be, since Task 3 has not written it).

This failure is the deliverable. Confirm the message names the start command.

- [ ] **Step 8: Verify the tier guard now passes**

Run: `./gradlew :spire-arch:test --tests '*TestTierCoverageTest*'`
Expected: PASS. Task 1's second failure is closed by the module now existing and being named in the tier.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts LICENSING.md spire-e2e
git commit -m "Add the spire-e2e module and its stack precondition"
```

---

### Task 3: The compose overlay

**Files:**
- Create: `deploy/compose.e2e.yml`
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/.gitkeep`
- Modify: `deploy/README.md` (a section describing the overlay and the deviation)

**Interfaces:**
- Consumes: `Stack.requireUp()` from Task 2.
- Produces: two containers reachable at the URLs `Stack` defaults to — GitLab on host `34780`, WireMock on host `34781` — and, on the compose network, `http://gitlab` and `http://llm-mock:8080`.

- [ ] **Step 1: Write the overlay**

Create `deploy/compose.e2e.yml`:

```yaml
# End-to-end overlay: a REAL GitLab and a fixture LLM, layered over the packaged stack.
#
#   docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env up -d --build
#
# Everything here exists because a WireMock stub of an SCM is our belief about that SCM's API, and the
# defect log in CLAUDE.md is a list of places that belief was wrong. GitLab is the only one of the
# three providers that can be self-hosted on the same API our adapter targets, so it is the only one
# this suite can cover. See docs/superpowers/specs/2026-08-29-gitlab-e2e-suite-design.md §2.
#
# Both services are on the packaged stack's default network, which is what removes the webhook tunnel:
# GitLab POSTs straight at http://ui:8080/webhooks/gitlab/{key}.

services:
  # SECURITY DEVIATION, deliberate and scoped. PublicHttpsGuard requires an https base URL resolving
  # to a PUBLIC address; a container GitLab is RFC1918, so provider registration would be refused.
  # The guard fires only at provider create/update, never on the review path, so this relaxes exactly
  # one setup call. Its production behaviour is NOT covered by this suite — ProviderUrlValidationTest
  # in testServices remains its only coverage.
  orchestrator:
    environment:
      SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS: "true"

  gitlab:
    # Pinned by tag AND digest. Detecting GitLab's own drift is a goal of this suite, but an unpinned
    # nightly failure is unreproducible — so the bump is a commit someone makes on purpose, and a
    # suite that breaks on that bump is the signal, not the noise.
    image: gitlab/gitlab-ce:17.11.1-ce.0
    hostname: gitlab
    environment:
      GITLAB_ROOT_PASSWORD: ${E2E_GITLAB_ROOT_PASSWORD:?set in deploy/.env}
      # Trim everything this suite does not use. The default image starts a container registry, a
      # Prometheus stack, Mattermost and Grafana; on a shared 4-vCPU runner they are the difference
      # between a five-minute boot and a job that times out.
      GITLAB_OMNIBUS_CONFIG: |
        external_url 'http://gitlab'
        registry['enable'] = false
        prometheus_monitoring['enable'] = false
        grafana['enable'] = false
        mattermost['enable'] = false
        gitlab_kas['enable'] = false
        puma['worker_processes'] = 2
        sidekiq['max_concurrency'] = 9
        gitlab_rails['initial_root_password'] = ENV['GITLAB_ROOT_PASSWORD']
    ports:
      - "${E2E_GITLAB_PORT:-34780}:80"
    shm_size: 256m
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost/-/readiness"]
      # start_period, not a long interval: readiness genuinely takes minutes, and without it every
      # early probe counts as a failure and the container is reported unhealthy before it ever had a
      # chance to answer.
      start_period: 10m
      interval: 15s
      timeout: 10s
      retries: 10
    restart: unless-stopped

  llm-mock:
    # The fixture LLM. It speaks the OpenAI-compatible wire, so spire-llm's parser, TokenUsageMapper
    # and finishReason -> outputCapped mapping all stay under test — the layer `spire.llm.provider=stub`
    # would skip. It is ALSO the prompt observer: WireMock's request journal is how the code-context
    # probes read what the worker actually sent the model.
    image: wiremock/wiremock:3.13.2
    command: ["--port", "8080", "--verbose", "--global-response-templating"]
    volumes:
      - ../spire-e2e/src/test/resources/llm-mock:/home/wiremock:ro
    ports:
      - "${E2E_LLM_MOCK_PORT:-34781}:8080"
    restart: unless-stopped
```

- [ ] **Step 2: Add the two new variables to the env example**

Append to `deploy/.env.example`, with the same no-defaults discipline the file already keeps:

```bash
# --- e2e overlay (deploy/compose.e2e.yml) only ---
# Obviously-synthetic. This GitLab is created, used and destroyed by the test suite.
E2E_GITLAB_ROOT_PASSWORD=TEST-gitlab-root-password
E2E_GITLAB_PORT=34780
E2E_LLM_MOCK_PORT=34781
```

- [ ] **Step 3: Create the mappings directory**

WireMock refuses to start cleanly with no mappings directory mounted.

```bash
mkdir -p spire-e2e/src/test/resources/llm-mock/mappings
touch spire-e2e/src/test/resources/llm-mock/mappings/.gitkeep
```

- [ ] **Step 4: Bring the stack up and run the health test**

```bash
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env up -d --build
```

Wait for GitLab. Poll rather than guess:

```bash
until curl -fsS http://localhost:34780/-/readiness >/dev/null 2>&1; do sleep 15; echo waiting; done
```

Then: `./gradlew :spire-e2e:test`
Expected: PASS. `StackHealthTest` is now the first green thing in this plan.

- [ ] **Step 5: Verify the deviation is really in effect**

Do not assume the environment override reached the container.

```bash
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env \
  exec orchestrator env | grep SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS
```
Expected: `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS=true`

If it is absent, the overlay's partial service definition did not merge — check that the service key is exactly `orchestrator`, matching `deploy/compose.yml`.

- [ ] **Step 6: Commit**

```bash
git add deploy/compose.e2e.yml deploy/.env.example deploy/README.md spire-e2e/src/test/resources/llm-mock
git commit -m "Add the e2e compose overlay with GitLab and a fixture LLM"
```

---

### Task 4: `Json`, `Await` and `Psql` — the three support pieces

Folded into one task because none is independently useful and each is small. All three are consumed by every task after this one.

**Files:**
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/Json.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/Await.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/Psql.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/support/AwaitTest.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/support/PsqlTest.java`

**Interfaces:**
- Consumes: `Stack` from Task 2.
- Produces:
  - `Json.read(String body)` → `JsonNode`; `Json.write(Object value)` → `String`.
  - `Await.until(String step, Duration deadline, Supplier<Optional<T>> probe)` → `T`, throws `AssertionError` naming the step and the last probe result.
  - `Await.absent(String step, Duration quietPeriod, Supplier<Long> count)` → `void`; samples `count` immediately, holds the quiet period, asserts it did not rise.
  - `Psql.rows(String sql)` → `List<List<String>>`; `Psql.one(String sql)` → `String`.

- [ ] **Step 1: Write `Json`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/Json.java`:

```java
package dev.codespire.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One mapper for the whole harness. Both drivers speak JSON and neither needs its own. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static JsonNode read(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("not JSON: " + abbreviate(body), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize " + value, e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        return body.length() <= 400 ? body : body.substring(0, 400) + "… (" + body.length() + " chars)";
    }
}
```

- [ ] **Step 2: Write the failing `Await` test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/AwaitTest.java`:

```java
package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwaitTest {

    @Test
    void returnsTheValueOnceTheProbeIsSatisfied() {
        AtomicInteger calls = new AtomicInteger();
        String found = Await.until("S1 comments posted", Duration.ofSeconds(5),
                () -> calls.incrementAndGet() < 3 ? Optional.empty() : Optional.of("ready"));

        assertEquals("ready", found);
        assertTrue(calls.get() >= 3);
    }

    @Test
    void failureNamesTheStepAndTheDeadline() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> Await.until("S9 verdict RESOLVED", Duration.ofMillis(600), Optional::empty));

        assertTrue(error.getMessage().contains("S9 verdict RESOLVED"),
                "the step name is the first thing a nightly failure report must carry: " + error.getMessage());
    }

    /**
     * The absence contract. Checking "nothing happened" immediately passes against a system that has
     * simply not got round to it yet, which is how S11 would have asserted nothing at all.
     */
    @Test
    void absenceFailsWhenTheCountRisesDuringTheQuietPeriod() {
        AtomicLong count = new AtomicLong(2);
        assertThrows(AssertionError.class,
                () -> Await.absent("S11 no new review", Duration.ofSeconds(1), count::incrementAndGet));
    }

    @Test
    void absencePassesWhenTheCountHolds() {
        AtomicLong count = new AtomicLong(2);
        Await.absent("S11 no new review", Duration.ofSeconds(1), count::get);
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*AwaitTest*'`
Expected: FAIL to compile — `Await` does not exist.

- [ ] **Step 4: Write `Await`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/Await.java`:

```java
package dev.codespire.e2e.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The async contract. Nothing in this pipeline is synchronous: reviews complete through Kafka and
 * GitLab delivers webhooks through Sidekiq, so every assertion is a race unless it waits.
 *
 * <p>Deadlines are generous and uniform rather than tuned per step. This is a nightly job, so one
 * flaky step costs a day of signal, and a step that genuinely needs its own longer deadline is
 * evidence of a problem rather than an invitation to tune.
 */
public final class Await {

    /** Uniform per-step deadline. Two model calls plus a webhook round trip fit inside it. */
    public static final Duration DEADLINE = Duration.ofMinutes(4);

    /** How long "nothing else happened" has to hold before it counts. */
    public static final Duration QUIET = Duration.ofSeconds(45);

    private static final Duration INTERVAL = Duration.ofSeconds(3);

    private Await() {
    }

    public static <T> T until(String step, Duration deadline, Supplier<Optional<T>> probe) {
        Instant giveUp = Instant.now().plus(deadline);
        RuntimeException lastError = null;
        int attempts = 0;
        while (Instant.now().isBefore(giveUp)) {
            attempts++;
            try {
                Optional<T> result = probe.get();
                if (result.isPresent()) {
                    return result.get();
                }
                lastError = null;
            } catch (RuntimeException e) {
                // A probe may legitimately fail while the system converges — a row that does not
                // exist yet, a thread not yet created. Keep the last one for the failure message
                // rather than aborting on it.
                lastError = e;
            }
            sleep(INTERVAL);
        }
        throw new AssertionError(step + " — not satisfied within " + deadline
                + " (" + attempts + " probes)"
                + (lastError == null ? "" : "; last probe error: " + lastError));
    }

    public static <T> T until(String step, Supplier<Optional<T>> probe) {
        return until(step, DEADLINE, probe);
    }

    /**
     * Asserts a count does NOT rise over a quiet period.
     *
     * <p>Callers must anchor this to a positive signal first — wait for the thing that SHOULD happen,
     * then call this. An absence assertion with nothing anchoring it passes against a system that has
     * not started yet, which is not an assertion.
     */
    public static void absent(String step, Duration quietPeriod, Supplier<Long> count) {
        long before = count.get();
        Instant until = Instant.now().plus(quietPeriod);
        while (Instant.now().isBefore(until)) {
            sleep(INTERVAL);
            long now = count.get();
            if (now != before) {
                throw new AssertionError(step + " — expected no change over " + quietPeriod
                        + ", but the count moved from " + before + " to " + now);
            }
        }
    }

    public static void absent(String step, Supplier<Long> count) {
        absent(step, QUIET, count);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting", e);
        }
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :spire-e2e:test --tests '*AwaitTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Write the failing `Psql` test**

`deploy/compose.yml` publishes no Postgres port, so the read model is reached the way `deploy/e2e.sh:135-139` already reaches it.

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/PsqlTest.java`:

```java
package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PsqlTest {

    @Test
    void readsTheOrchestratorSchema() {
        String count = Psql.one("SELECT count(*) FROM orchestrator.review_status");
        assertTrue(Long.parseLong(count) >= 0);
    }

    @Test
    void readsTheWorkerSchema() {
        String count = Psql.one("SELECT count(*) FROM worker.comment_idempotency");
        assertTrue(Long.parseLong(count) >= 0);
    }

    @Test
    void splitsColumnsAndRows() {
        assertEquals(java.util.List.of(java.util.List.of("1", "two")), Psql.rows("SELECT 1, 'two'"));
    }
}
```

The two-schema test is deliberate: it pins that the superuser from `deploy/.env` can read both, which the per-service roles cannot, and it fails loudly if someone later points this at a narrower role.

- [ ] **Step 7: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*PsqlTest*'`
Expected: FAIL to compile — `Psql` does not exist.

- [ ] **Step 8: Write `Psql`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/Psql.java`:

```java
package dev.codespire.e2e.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the read model through `docker compose exec postgres psql`, as deploy/e2e.sh:135-139 does.
 *
 * <p>Postgres has no published host port in deploy/compose.yml, and publishing one from the overlay
 * would weaken the claim that the overlay only adds. The cost of that choice is here: results arrive
 * as text, so every caller gets a small typed read rather than matching strings at the call site.
 */
public final class Psql {

    private static final String SEPARATOR = "";

    private Psql() {
    }

    /** @return the single value of a single-row, single-column query. */
    public static String one(String sql) {
        List<List<String>> rows = rows(sql);
        if (rows.size() != 1 || rows.getFirst().size() != 1) {
            throw new IllegalStateException("expected exactly one value from `" + sql + "`, got " + rows);
        }
        return rows.getFirst().getFirst();
    }

    public static List<List<String>> rows(String sql) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "compose",
                "-f", "deploy/compose.yml",
                "-f", "deploy/compose.e2e.yml",
                "--env-file", "deploy/.env",
                "exec", "-T",
                "-e", "PGPASSWORD=" + required("POSTGRES_PASSWORD"),
                "postgres",
                "psql", "-U", required("POSTGRES_USER"), "-d", required("POSTGRES_DB"),
                // -t strips headers, -A unaligned, -F our own separator: a tab or pipe would be
                // ambiguous inside a finding message, which routinely contains both.
                "-tA", "-F", SEPARATOR, "-c", sql));

        String output = run(command);
        List<List<String>> rows = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(List.of(line.split(SEPARATOR, -1)));
        }
        return rows;
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("psql timed out: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("psql exited " + process.exitValue() + ": " + output);
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("could not run psql — is the stack up?", e);
        }
    }

    private static java.io.File repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — see spire-e2e/build.gradle.kts");
        }
        return new java.io.File(root);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is unset. Source deploy/.env before running: "
                    + "`set -a; . deploy/.env; set +a`");
        }
        return value;
    }
}
```

- [ ] **Step 9: Pass the repo root to the test JVM**

`Psql` needs to run `docker compose` from the repo root. Add to `spire-e2e/build.gradle.kts` inside `tasks.test`:

```kotlin
    // psql runs `docker compose` with repo-relative -f paths, so it needs the root explicitly rather
    // than guessing from the working directory — which differs between Gradle and an IDE run.
    systemProperty("spire.repoRoot", rootProject.projectDir.absolutePath)
```

- [ ] **Step 10: Run it to verify it passes**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*PsqlTest*'
```
Expected: PASS, 3 tests.

- [ ] **Step 11: Commit**

```bash
git add spire-e2e/build.gradle.kts spire-e2e/src/test/java/dev/codespire/e2e/support
git commit -m "Add JSON, await and psql support for the e2e harness"
```

---

### Task 5: The GitLab driver

Drives GitLab the way a human contributor would. Deliberately **not** built on our `spire-scm-gitlab` adapter: a harness that drives with the code under test confirms itself.

**Files:**
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/Rails.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/GitLabDriver.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/GitLabDriverTest.java`

**Interfaces:**
- Consumes: `Stack`, `Json`, `Await` from Tasks 2 and 4.
- Produces:
  - `Rails.run(String script)` → `String` (stdout of `gitlab-rails runner`).
  - `GitLabDriver.asRoot()` / `GitLabDriver.as(String token)` → `GitLabDriver`.
  - `GitLabDriver.ensureUser(String username, String email, String password)` → `long` user id.
  - `GitLabDriver.mintToken(String username, String tokenValue)` → `void`.
  - `GitLabDriver.allowLocalWebhooks()` → `void`.
  - `GitLabDriver.createProject(String name)` → `long` project id.
  - `GitLabDriver.addMember(long projectId, long userId)` → `void`.
  - `GitLabDriver.commit(long projectId, String branch, String startBranch, String message, List<FileAction> actions)` → `String` commit sha.
  - `GitLabDriver.FileAction` — `record FileAction(String action, String filePath, String content, String previousPath)` with factories `create`, `update`, `delete`, `move`.
  - `GitLabDriver.openMergeRequest(long projectId, String sourceBranch, String targetBranch, String title)` → `long` iid.
  - `GitLabDriver.mergeRequestNotes(long projectId, long iid)` → `JsonNode` array.
  - `GitLabDriver.discussions(long projectId, long iid)` → `JsonNode` array.
  - `GitLabDriver.replyToDiscussion(long projectId, long iid, String discussionId, String body)` → `void`.
  - `GitLabDriver.addNote(long projectId, long iid, String body)` → `void`.
  - `GitLabDriver.createDiscussionOnLine(long projectId, long iid, String path, int newLine, String body)` → `void`.
  - `GitLabDriver.mergeMergeRequest(long projectId, long iid)` → `void`.
  - `GitLabDriver.createWebhook(long projectId, String url, String secretToken)` → `void`.
  - `GitLabDriver.deleteProjectsNamed(String prefix)` → `void`.

- [ ] **Step 1: Write the failing driver test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/GitLabDriverTest.java`:

```java
package dev.codespire.e2e.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabDriverTest {

    @Test
    void mintsAKnownTokenForANewUser() {
        GitLabDriver root = GitLabDriver.asRoot();
        root.ensureUser("e2e-driver-probe", "e2e-driver-probe@example.invalid", "TEST-password-123");
        root.mintToken("e2e-driver-probe", "TEST-token-driver-probe");

        JsonNode me = GitLabDriver.as("TEST-token-driver-probe").get("/user");
        assertEquals("e2e-driver-probe", me.get("username").asText());
    }

    @Test
    void allowsLocalWebhookTargets() {
        GitLabDriver root = GitLabDriver.asRoot();
        root.allowLocalWebhooks();

        JsonNode settings = root.get("/application/settings");
        assertTrue(settings.get("allow_local_requests_from_web_hooks_and_services").asBoolean(),
                "GitLab blocks private-network webhook targets by default. Without this the gateway "
                        + "never receives a delivery, and the symptom is indistinguishable from a "
                        + "policy decline: nothing arrives, so nothing is logged on our side.");
    }

    @Test
    void commitsAndOpensAMergeRequest() {
        GitLabDriver root = GitLabDriver.asRoot();
        long project = root.createProject("e2e-driver-probe-" + System.currentTimeMillis());

        root.commit(project, "main", null, "Add a starter file",
                List.of(FileAction.create("README.md", "E2E probe\n")));
        String head = root.commit(project, "topic", "main", "Add a second file",
                List.of(FileAction.create("second.txt", "second\n")));
        assertFalse(head.isBlank());

        long iid = root.openMergeRequest(project, "topic", "main", "E2E driver probe");
        assertTrue(iid > 0);

        root.deleteProjectsNamed("e2e-driver-probe-");
    }

    @Test
    void movesAFileInOneCommit() {
        GitLabDriver root = GitLabDriver.asRoot();
        long project = root.createProject("e2e-move-probe-" + System.currentTimeMillis());
        root.commit(project, "main", null, "Add",
                List.of(FileAction.create("old/Name.java", "class Name {}\n")));

        String moved = root.commit(project, "main", null, "Move",
                List.of(FileAction.move("new/Name.java", "old/Name.java", "class Name {}\n")));
        assertNotEquals("", moved);

        root.deleteProjectsNamed("e2e-move-probe-");
    }
}
```

The move test exists because MR 4 depends on a *100%-similarity* rename, and a move expressed as delete-plus-create would not produce one — it would test a different thing and quietly report the wrong answer.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*GitLabDriverTest*'`
Expected: FAIL to compile — `GitLabDriver` does not exist.

- [ ] **Step 3: Write `Rails`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/Rails.java`:

```java
package dev.codespire.e2e.gitlab;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * `gitlab-rails runner` inside the GitLab container.
 *
 * <p>Used for exactly one thing the REST API cannot do: mint a personal access token whose VALUE we
 * choose. The API returns a token it generated, which is fine for a human and useless for a fixture —
 * the harness needs the token before it can make the call that would create it.
 *
 * <p>Each call boots a Rails environment and takes tens of seconds, so batch work into one script
 * rather than calling this in a loop.
 */
final class Rails {

    private Rails() {
    }

    static String run(String script) {
        List<String> command = List.of(
                "docker", "compose",
                "-f", "deploy/compose.yml",
                "-f", "deploy/compose.e2e.yml",
                "--env-file", "deploy/.env",
                "exec", "-T", "gitlab",
                "gitlab-rails", "runner", script);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(new java.io.File(System.getProperty("spire.repoRoot")))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("gitlab-rails runner timed out. Output so far: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "gitlab-rails runner exited " + process.exitValue() + ": " + output);
            }
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("gitlab-rails runner failed for script: " + script, e);
        }
    }
}
```

- [ ] **Step 4: Write `GitLabDriver`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/gitlab/GitLabDriver.java`:

```java
package dev.codespire.e2e.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives GitLab as a contributor would.
 *
 * <p>Deliberately hand-rolled rather than built on spire-scm-gitlab. A harness that drives the system
 * with the code under test confirms itself: a wrong belief about the API would be shared by the
 * driver and the adapter, and the test would agree with the bug.
 */
public final class GitLabDriver {

    private final String token;

    private GitLabDriver(String token) {
        this.token = token;
    }

    public static GitLabDriver asRoot() {
        return new GitLabDriver(rootToken());
    }

    public static GitLabDriver as(String token) {
        return new GitLabDriver(token);
    }

    /**
     * The root token is minted once and reused. Its value is fixed and obviously synthetic, so a run
     * is reproducible and nothing has to be scraped out of a log.
     */
    private static String rootToken() {
        String value = "TEST-e2e-root-token-000000000000";
        Rails.run("""
                user = User.find_by_username('root')
                user.personal_access_tokens.where(name: 'e2e-root').delete_all
                token = user.personal_access_tokens.create!(
                  scopes: ['api', 'sudo'], name: 'e2e-root', expires_at: 1.day.from_now)
                token.set_token('%s')
                token.save!
                """.formatted(value));
        return value;
    }

    // --- users -----------------------------------------------------------------------------------

    public long ensureUser(String username, String email, String password) {
        String output = Rails.run("""
                user = User.find_by_username('%s')
                if user.nil?
                  user = User.new(username: '%s', email: '%s', name: '%s',
                                  password: '%s', password_confirmation: '%s')
                  user.skip_confirmation!
                  user.save!
                end
                puts user.id
                """.formatted(username, username, email, username, password, password));
        return Long.parseLong(lastLine(output));
    }

    public void mintToken(String username, String tokenValue) {
        Rails.run("""
                user = User.find_by_username('%s')
                user.personal_access_tokens.where(name: 'e2e').delete_all
                token = user.personal_access_tokens.create!(
                  scopes: ['api'], name: 'e2e', expires_at: 1.day.from_now)
                token.set_token('%s')
                token.save!
                """.formatted(username, tokenValue));
    }

    // --- instance settings -----------------------------------------------------------------------

    /**
     * GitLab refuses webhook deliveries to private networks by default, which is the mirror image of
     * our own SSRF guard. Asserted by the caller rather than assumed: a silently-unapplied setting
     * presents as the bot going quiet.
     */
    public void allowLocalWebhooks() {
        put("/application/settings?allow_local_requests_from_web_hooks_and_services=true", null);
    }

    // --- projects and commits --------------------------------------------------------------------

    public long createProject(String name) {
        JsonNode created = post("/projects", Map.of(
                "name", name,
                "path", name,
                "visibility", "private",
                "initialize_with_readme", false));
        return created.get("id").asLong();
    }

    public void addMember(long projectId, long userId) {
        // access_level 40 is Maintainer: enough to push, comment, resolve and merge.
        post("/projects/" + projectId + "/members",
                Map.of("user_id", userId, "access_level", 40));
    }

    public record FileAction(String action, String filePath, String content, String previousPath) {

        public static FileAction create(String path, String content) {
            return new FileAction("create", path, content, null);
        }

        public static FileAction update(String path, String content) {
            return new FileAction("update", path, content, null);
        }

        public static FileAction delete(String path) {
            return new FileAction("delete", path, null, null);
        }

        /**
         * A real move. Expressed as delete-plus-create it would produce a 0%-similarity change, and
         * MR 4 is specifically about a 100%-similarity rename — the two are different inputs and
         * would answer different questions.
         */
        public static FileAction move(String newPath, String previousPath, String content) {
            return new FileAction("move", newPath, content, previousPath);
        }
    }

    /** @param startBranch the branch to fork from, or null to commit onto an existing branch. */
    public String commit(long projectId, String branch, String startBranch, String message,
                         List<FileAction> actions) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (FileAction action : actions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("action", action.action());
            entry.put("file_path", action.filePath());
            if (action.previousPath() != null) {
                entry.put("previous_path", action.previousPath());
            }
            if (action.content() != null) {
                entry.put("content", action.content());
            }
            payload.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch", branch);
        if (startBranch != null) {
            body.put("start_branch", startBranch);
        }
        body.put("commit_message", message);
        body.put("actions", payload);

        return post("/projects/" + projectId + "/repository/commits", body).get("id").asText();
    }

    public long openMergeRequest(long projectId, String sourceBranch, String targetBranch, String title) {
        return post("/projects/" + projectId + "/merge_requests", Map.of(
                "source_branch", sourceBranch,
                "target_branch", targetBranch,
                "title", title)).get("iid").asLong();
    }

    public void mergeMergeRequest(long projectId, long iid) {
        put("/projects/" + projectId + "/merge_requests/" + iid + "/merge", Map.of());
    }

    // --- conversation ----------------------------------------------------------------------------

    public JsonNode mergeRequestNotes(long projectId, long iid) {
        return get("/projects/" + projectId + "/merge_requests/" + iid + "/notes?per_page=100");
    }

    public JsonNode discussions(long projectId, long iid) {
        return get("/projects/" + projectId + "/merge_requests/" + iid + "/discussions?per_page=100");
    }

    public void addNote(long projectId, long iid, String body) {
        post("/projects/" + projectId + "/merge_requests/" + iid + "/notes", Map.of("body", body));
    }

    public void replyToDiscussion(long projectId, long iid, String discussionId, String body) {
        post("/projects/" + projectId + "/merge_requests/" + iid + "/discussions/" + discussionId
                + "/notes", Map.of("body", body));
    }

    /**
     * Opens a new discussion anchored to a NEW-side line. The position needs the MR's own diff_refs,
     * which is why they are fetched here rather than passed in.
     */
    public void createDiscussionOnLine(long projectId, long iid, String path, int newLine, String body) {
        JsonNode mr = get("/projects/" + projectId + "/merge_requests/" + iid);
        JsonNode refs = mr.get("diff_refs");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("position", Map.of(
                "base_sha", refs.get("base_sha").asText(),
                "start_sha", refs.get("start_sha").asText(),
                "head_sha", refs.get("head_sha").asText(),
                "position_type", "text",
                "new_path", path,
                "new_line", newLine));
        post("/projects/" + projectId + "/merge_requests/" + iid + "/discussions", payload);
    }

    // --- webhooks and cleanup --------------------------------------------------------------------

    public void createWebhook(long projectId, String url, String secretToken) {
        post("/projects/" + projectId + "/hooks", Map.of(
                "url", url,
                "token", secretToken,
                // Both, or the conversation scenarios receive nothing: merge_requests_events carries
                // open/update/merge, note_events carries every comment.
                "merge_requests_events", true,
                "note_events", true,
                "push_events", false,
                "enable_ssl_verification", false));
    }

    /**
     * A long-lived local stack would otherwise accumulate one project per run forever. Deleting by
     * prefix rather than by id means a run cleans up after runs that crashed before their own cleanup.
     */
    public void deleteProjectsNamed(String prefix) {
        JsonNode projects = get("/projects?owned=true&per_page=100&search="
                + URLEncoder.encode(prefix, StandardCharsets.UTF_8));
        for (JsonNode project : projects) {
            if (project.get("path").asText().startsWith(prefix)) {
                delete("/projects/" + project.get("id").asLong());
            }
        }
    }

    // --- transport -------------------------------------------------------------------------------

    public JsonNode get(String path) {
        return send(request(path).GET());
    }

    private JsonNode post(String path, Object body) {
        return send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private JsonNode put(String path, Object body) {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body));
        return send(request(path).header("Content-Type", "application/json").PUT(payload));
    }

    private void delete(String path) {
        send(request(path).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(Stack.gitlabBaseUrl() + "/api/v4" + path))
                .timeout(Duration.ofSeconds(60))
                .header("PRIVATE-TOKEN", token);
    }

    private JsonNode send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    Stack.http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("GitLab " + response.statusCode() + " for "
                        + builder.build().uri() + ": " + response.body());
            }
            return response.body().isBlank() ? Json.read("{}") : Json.read(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("GitLab request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling GitLab", e);
        }
    }

    private static String lastLine(String output) {
        String[] lines = output.strip().split("\n");
        return lines[lines.length - 1].strip();
    }
}
```

- [ ] **Step 5: Run it to verify it passes**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*GitLabDriverTest*'
```
Expected: PASS, 4 tests. The first run is slow — each `Rails.run` boots a Rails environment.

- [ ] **Step 6: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e/gitlab
git commit -m "Add a GitLab driver for the e2e harness"
```

---

### Task 6: The Spire driver and the LLM mock reader

**Files:**
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/spire/SpireDriver.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMock.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/spire/SpireDriverTest.java`

**Interfaces:**
- Consumes: `Stack`, `Json` from Tasks 2 and 4.
- Produces:
  - `SpireDriver.SpireDriver()` — mints and caches an operator token on construction.
  - `SpireDriver.registerScmProvider(String name, String baseUrl, String workspace, String token)` → `String` provider id.
  - `SpireDriver.registerLlmProvider(String name, String baseUrl, String model)` → `String` provider id.
  - `SpireDriver.catalogueUnmeteredModel(String name, String label)` → `String` model id.
  - `SpireDriver.registerWebhook(String providerType, String target)` → `Webhook`, a `record Webhook(String key, String secret)`.
  - `SpireDriver.setReviewMode(String mode)` → `void`.
  - `SpireDriver.reviewSummary(String workspace, String slug, long pr)` → `JsonNode`.
  - `LlmMock.reset()` → `void`; `LlmMock.prompts()` → `List<String>` of request bodies, oldest first.

- [ ] **Step 1: Write the failing test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/spire/SpireDriverTest.java`:

```java
package dev.codespire.e2e.spire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpireDriverTest {

    @Test
    void mintsAnOperatorTokenThatTheApiAccepts() {
        SpireDriver spire = new SpireDriver();
        assertFalse(spire.operatorToken().isBlank());
        assertNotNull(spire.get("/api/providers"));
    }

    @Test
    void setsAndReadsBackTheReviewMode() {
        SpireDriver spire = new SpireDriver();
        spire.setReviewMode("active");
        assertEquals("active", spire.get("/api/settings/review-mode").get("mode").asText());
    }

    @Test
    void registersAWebhookAndGetsTheSecretExactlyOnce() {
        SpireDriver spire = new SpireDriver();
        SpireDriver.Webhook hook = spire.registerWebhook("gitlab", "e2e-probe/e2e-probe");

        assertFalse(hook.key().isBlank());
        assertFalse(hook.secret().isBlank(),
                "the secret is returned only on create — GitLab's Secret token field needs it, and "
                        + "the view thereafter carries only hasSecret");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*SpireDriverTest*'`
Expected: FAIL to compile — `SpireDriver` does not exist.

- [ ] **Step 3: Write `SpireDriver`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/spire/SpireDriver.java`:

```java
package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Our own REST surface, reached the way a browser reaches it: through the dashboard's nginx, on one
 * origin. ADR-022 gives each service its own URL prefix — the orchestrator answers under /api, the
 * gateway under /gw — and this driver keeps that distinction visible at every call site.
 */
public final class SpireDriver {

    private final String token;

    public SpireDriver() {
        this.token = mintOperatorToken();
    }

    public String operatorToken() {
        return token;
    }

    private static String mintOperatorToken() {
        String form = "grant_type=password"
                + "&client_id=spire-orchestrator"
                + "&client_secret=" + required("SPIRE_OIDC_ORCHESTRATOR_SECRET")
                + "&username=operator"
                + "&password=" + required("DEV_OPERATOR_PASSWORD");
        try {
            HttpResponse<String> response = Stack.http().send(
                    HttpRequest.newBuilder(URI.create(
                                    Stack.keycloakBaseUrl() + "/realms/spire/protocol/openid-connect/token"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Keycloak refused the operator password grant ("
                        + response.statusCode() + "): " + response.body());
            }
            return Json.read(response.body()).get("access_token").asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not reach Keycloak", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted minting a token", e);
        }
    }

    // --- registration ----------------------------------------------------------------------------

    public String registerScmProvider(String name, String baseUrl, String workspace, String apiToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "gitlab");
        body.put("baseUrl", baseUrl);
        body.put("workspace", workspace);
        body.put("authKind", "bearer");
        body.put("secret", apiToken);
        body.put("enabled", true);
        body.put("authors", List.of());
        return post("/api/providers", body).get("id").asText();
    }

    /**
     * Synchronously validates the key with GET {baseUrl}/models, so llm-mock must already be stubbing
     * /v1/models before this runs or the call answers 400 and setup dies here.
     */
    public String registerLlmProvider(String name, String baseUrl, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "openai");
        body.put("baseUrl", baseUrl);
        body.put("apiKey", "TEST-mock-key");
        body.put("model", model);
        body.put("enabled", true);
        body.put("isDefault", true);
        return post("/api/llm-providers", body).get("id").asText();
    }

    /**
     * UNMETERED, never a rate. ADR-023's pre-spend guard refuses an unpriceable model, so the model
     * must be catalogued before a review can run — and inventing a price for a mock would be
     * fabricated data in the one table the project keeps precisely so money is never guessed.
     */
    public String catalogueUnmeteredModel(String name, String label) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "openai");
        body.put("name", name);
        body.put("label", label);
        body.put("pricingMode", "UNMETERED");
        body.put("rates", Map.of());
        body.put("enabled", true);
        return post("/api/llm-models", body).get("id").asText();
    }

    public record Webhook(String key, String secret) {
    }

    public Webhook registerWebhook(String providerType, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerType", providerType);
        body.put("scope", "repo");
        body.put("target", target);
        body.put("enabled", true);

        JsonNode created = post("/gw/webhook-repos", body);
        return new Webhook(
                created.get("repo").get("webhookKey").asText(),
                created.get("secret").asText());
    }

    public void setReviewMode(String mode) {
        put("/api/settings/review-mode", Map.of("mode", mode));
    }

    // --- reads -----------------------------------------------------------------------------------

    public JsonNode reviewSummary(String workspace, String slug, long pr) {
        return get("/api/reviews/" + enc(workspace) + "/" + enc(slug) + "/" + pr);
    }

    // --- transport -------------------------------------------------------------------------------

    public JsonNode get(String path) {
        return send(request(path).GET());
    }

    private JsonNode post(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private JsonNode put(String path, Object body) {
        return send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(body))));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(Stack.uiBaseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    Stack.http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("spire " + response.statusCode() + " for "
                        + builder.build().uri() + ": " + response.body());
            }
            return response.body().isBlank() ? Json.read("{}") : Json.read(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("spire request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling spire", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is unset. Source deploy/.env first: "
                    + "`set -a; . deploy/.env; set +a`");
        }
        return value;
    }
}
```

- [ ] **Step 4: Write `LlmMock`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMock.java`:

```java
package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads WireMock's request journal.
 *
 * <p>This is the only place in the harness that sees inside a review, and it sees it the way the model
 * does: the exact prompt text the worker sent. That removes the need to enable PromptLog — which is
 * opt-in and off by default precisely because a rendered prompt quotes source code — just so a test
 * can observe what was assembled.
 */
public final class LlmMock {

    private LlmMock() {
    }

    /** Clears the journal. Call before a scenario, so `prompts()` describes that scenario alone. */
    public static void reset() {
        send(HttpRequest.newBuilder(URI.create(Stack.llmMockAdminUrl() + "/requests"))
                .timeout(Duration.ofSeconds(30))
                .DELETE());
    }

    /** @return every request body the mock received, oldest first. */
    public static List<String> prompts() {
        JsonNode journal = Json.read(send(
                HttpRequest.newBuilder(URI.create(Stack.llmMockAdminUrl() + "/requests"))
                        .timeout(Duration.ofSeconds(30))
                        .GET()));

        List<String> bodies = new ArrayList<>();
        for (JsonNode entry : journal.get("requests")) {
            JsonNode request = entry.get("request");
            if (request.get("url").asText().contains("/chat/completions")) {
                bodies.add(request.get("body").asText());
            }
        }
        // WireMock returns newest first; scenarios read in the order the calls happened.
        return bodies.reversed();
    }

    private static String send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response =
                    Stack.http().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("llm-mock admin " + response.statusCode()
                        + ": " + response.body());
            }
            return response.body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("llm-mock admin unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted calling llm-mock admin", e);
        }
    }
}
```

- [ ] **Step 5: Run it to verify it fails for the right reason**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*SpireDriverTest*'
```
Expected: the first two tests PASS; `registersAWebhookAndGetsTheSecretExactlyOnce` may PASS. If any test fails with a 401, the realm's `operator` user or the client secret in `deploy/.env` is wrong — fix that before continuing, since every later task depends on it.

- [ ] **Step 6: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e/spire
git commit -m "Add a driver for the spire REST surface and the mock journal"
```

---

### Task 7: The LLM mock's three call kinds — walking skeleton part 1

**Files:**
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/models.json`
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/review-defects.json`
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/review-clean.json`
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/reconcile.json`
- Create: `spire-e2e/src/test/resources/llm-mock/mappings/followup.json`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMockContractTest.java`

**Interfaces:**
- Consumes: `Stack`, `Json` from Tasks 2 and 4.
- Produces: a mock that answers `/v1/models`, and answers `/v1/chat/completions` with a response whose *shape* matches the call kind and whose *content* is keyed to the markers on added diff lines.

**The discriminator.** Match on `PromptCatalog.lockedSystemSuffix`, whose three values are textually distinct (`PromptCatalog.java:21`, `:39`, `:42`). It is chosen because it is *locked*: per-repository prompt customization can rewrite a persona or body, so matching on those would break the suite the day someone overrides a prompt — a supported feature this suite must not be hostile to.

| Kind | Substring present in the request body |
|---|---|
| `REVIEW` | `one-paragraph overall assessment` |
| `RECONCILE` | `Respond ONLY with JSON: {\"verdicts\"` |
| `FOLLOWUP` | `Respond with ONLY the reply to post in the thread` |

- [ ] **Step 1: Write the failing contract test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMockContractTest.java`:

```java
package dev.codespire.e2e.spire;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.support.Json;
import dev.codespire.e2e.support.Stack;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the mock's own contract, without any of our services in the picture. A mock that answers the
 * wrong shape produces a review failure three layers away, so it is cheaper to prove it here.
 */
class LlmMockContractTest {

    private static final String REVIEW_MARKER = "one-paragraph overall assessment";

    private static final String RECONCILE_MARKER = "Respond ONLY with JSON: {\\\"verdicts\\\"";

    private static final String FOLLOWUP_MARKER = "Respond with ONLY the reply to post in the thread";

    @Test
    void servesTheModelListRegistrationValidatesAgainst() {
        String body = get("/v1/models");
        assertTrue(body.contains("e2e-mock-model"),
                "registerLlmProvider validates the key with GET {baseUrl}/models, so this stub is a "
                        + "prerequisite of setup, not a convenience");
    }

    @Test
    void aReviewPromptWithAnAddedMarkerReturnsAFinding() {
        JsonNode reply = completion(REVIEW_MARKER + "\n+ int x = 1 / 0;  // E2E-DEFECT-A\n");
        String content = reply.get("choices").get(0).get("message").get("content").asText();

        assertTrue(content.contains("\"findings\""), content);
        assertTrue(content.contains("E2E-DEFECT-A"), content);
    }

    /**
     * The inversion this design was rebuilt around. After a fix commit the deleted line appears in the
     * incremental diff as a REMOVED line, so naive presence-matching reports the finding precisely
     * when the defect is gone.
     */
    @Test
    void aRemovedMarkerLineDoesNotProduceAFinding() {
        JsonNode reply = completion(REVIEW_MARKER + "\n- int x = 1 / 0;  // E2E-DEFECT-A\n");
        String content = reply.get("choices").get(0).get("message").get("content").asText();

        assertFalse(content.contains("E2E-DEFECT-A"),
                "a removed marker line must not be read as a present defect: " + content);
    }

    @Test
    void aReconcilePromptReturnsVerdictsNotFindings() {
        JsonNode reply = completion(RECONCILE_MARKER + "\n- int x = 1 / 0;  // E2E-DEFECT-A\n");
        String content = reply.get("choices").get(0).get("message").get("content").asText();

        assertTrue(content.contains("\"verdicts\""), content);
        assertFalse(content.contains("\"findings\""), content);
    }

    @Test
    void aFollowUpPromptReturnsFencedProseNotJson() {
        JsonNode reply = completion(FOLLOWUP_MARKER + "\nauthor asks a question\n");
        String content = reply.get("choices").get(0).get("message").get("content").asText();

        assertFalse(content.strip().startsWith("{"), "a follow-up reply is prose, not JSON: " + content);
        assertTrue(content.contains("```"),
                "the locked FOLLOWUP contract requires fenced code — indented code renders as prose");
    }

    private static JsonNode completion(String prompt) {
        String body = """
                {"model":"e2e-mock-model","messages":[{"role":"system","content":%s},
                {"role":"user","content":"diff"}]}
                """.formatted(Json.write(prompt));
        return Json.read(post("/v1/chat/completions", body));
    }

    private static String get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).GET());
    }

    private static String post(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String base() {
        return Stack.llmMockAdminUrl().replace("/__admin", "");
    }

    private static String send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = Stack.http().send(
                    builder.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("llm-mock " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("llm-mock unreachable", e);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*LlmMockContractTest*'`
Expected: FAIL — WireMock answers 404 for every request; no mappings exist.

- [ ] **Step 3: Write the models stub**

Create `spire-e2e/src/test/resources/llm-mock/mappings/models.json`:

```json
{
  "priority": 1,
  "request": { "method": "GET", "urlPath": "/v1/models" },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "object": "list",
      "data": [{ "id": "e2e-mock-model", "object": "model", "owned_by": "e2e" }]
    }
  }
}
```

- [ ] **Step 4: Write the review stubs**

Two mappings, ordered by priority. The specific one matches a prompt containing an **added** marker line; the fallback answers a clean review.

Create `spire-e2e/src/test/resources/llm-mock/mappings/review-defects.json`:

```json
{
  "priority": 2,
  "request": {
    "method": "POST",
    "urlPath": "/v1/chat/completions",
    "bodyPatterns": [
      { "contains": "one-paragraph overall assessment" },
      { "matches": "(?s).*\\\\n\\\\+[^\\\\n]*E2E-DEFECT-A.*" }
    ]
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "id": "chatcmpl-e2e-review",
      "object": "chat.completion",
      "model": "e2e-mock-model",
      "choices": [
        {
          "index": 0,
          "finish_reason": "stop",
          "message": {
            "role": "assistant",
            "content": "{\"summary\":\"E2E fixture review.\",\"findings\":[{\"path\":\"src/main/java/e2e/Defects.java\",\"line\":7,\"endLine\":7,\"severity\":\"BLOCKER\",\"message\":\"E2E-DEFECT-A: division by zero.\",\"suggestion\":null},{\"path\":\"src/main/java/e2e/Defects.java\",\"line\":12,\"endLine\":12,\"severity\":\"MAJOR\",\"message\":\"E2E-DEFECT-B: unchecked index.\",\"suggestion\":null},{\"path\":\"src/ui/defects.ts\",\"line\":5,\"endLine\":5,\"severity\":\"MINOR\",\"message\":\"E2E-DEFECT-C: unused binding.\",\"suggestion\":null}]}"
          }
        }
      ],
      "usage": { "prompt_tokens": 1200, "completion_tokens": 180, "total_tokens": 1380 }
    }
  }
}
```

Create `spire-e2e/src/test/resources/llm-mock/mappings/review-clean.json`:

```json
{
  "priority": 5,
  "request": {
    "method": "POST",
    "urlPath": "/v1/chat/completions",
    "bodyPatterns": [{ "contains": "one-paragraph overall assessment" }]
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "id": "chatcmpl-e2e-review-clean",
      "object": "chat.completion",
      "model": "e2e-mock-model",
      "choices": [
        {
          "index": 0,
          "finish_reason": "stop",
          "message": {
            "role": "assistant",
            "content": "{\"summary\":\"E2E fixture review: nothing to report.\",\"findings\":[]}"
          }
        }
      ],
      "usage": { "prompt_tokens": 900, "completion_tokens": 40, "total_tokens": 940 }
    }
  }
}
```

- [ ] **Step 5: Write the reconcile and follow-up stubs**

Create `spire-e2e/src/test/resources/llm-mock/mappings/reconcile.json`:

```json
{
  "priority": 3,
  "request": {
    "method": "POST",
    "urlPath": "/v1/chat/completions",
    "bodyPatterns": [{ "contains": "Respond ONLY with JSON: {\\\"verdicts\\\"" }]
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "id": "chatcmpl-e2e-reconcile",
      "object": "chat.completion",
      "model": "e2e-mock-model",
      "choices": [
        {
          "index": 0,
          "finish_reason": "stop",
          "message": {
            "role": "assistant",
            "content": "{\"verdicts\":[{\"id\":1,\"status\":\"resolved\",\"note\":\"E2E: the division is gone.\"},{\"id\":2,\"status\":\"still-open\",\"note\":\"E2E: the bound check was added but the negative index is still unhandled.\"},{\"id\":3,\"status\":\"unchanged\",\"note\":\"E2E: untouched.\"}]}"
          }
        }
      ],
      "usage": { "prompt_tokens": 800, "completion_tokens": 90, "total_tokens": 890 }
    }
  }
}
```

Create `spire-e2e/src/test/resources/llm-mock/mappings/followup.json`:

```json
{
  "priority": 3,
  "request": {
    "method": "POST",
    "urlPath": "/v1/chat/completions",
    "bodyPatterns": [{ "contains": "Respond with ONLY the reply to post in the thread" }]
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": {
      "id": "chatcmpl-e2e-followup",
      "object": "chat.completion",
      "model": "e2e-mock-model",
      "choices": [
        {
          "index": 0,
          "finish_reason": "stop",
          "message": {
            "role": "assistant",
            "content": "E2E fixture reply. Here is the shape being discussed:\n\n```java\nint safe = denominator == 0 ? 0 : numerator / denominator;\n```\n\nThat is the whole answer for this thread."
          }
        }
      ],
      "usage": { "prompt_tokens": 700, "completion_tokens": 60, "total_tokens": 760 }
    }
  }
}
```

- [ ] **Step 6: Reload the mock and run the test**

WireMock reads mappings at start; the overlay mounts them read-only, so a change needs a reload:

```bash
curl -X POST http://localhost:34781/__admin/mappings/reset
```

Run: `./gradlew :spire-e2e:test --tests '*LlmMockContractTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 7: Verify the marker regex actually discriminates**

A pattern that matched everything would pass four of these five tests. Break it deliberately and confirm exactly one test fails:

Temporarily change `review-defects.json`'s second `bodyPatterns` entry to `{ "contains": "E2E-DEFECT-A" }`, reset the mappings, and re-run.
Expected: `aRemovedMarkerLineDoesNotProduceAFinding` FAILS and the other four pass.

Revert the change, reset the mappings again, and confirm all five pass. Do not skip this — the added-lines-only rule is the fix for the defect that invalidated the design's first draft, and a rule nothing proves is a rule nobody has.

- [ ] **Step 8: Commit**

```bash
git add spire-e2e/src/test/resources/llm-mock spire-e2e/src/test/java/dev/codespire/e2e/spire/LlmMockContractTest.java
git commit -m "Add LLM mock fixtures for review, reconcile and follow-up"
```

---

### Task 8: Fixtures and the setup phase — walking skeleton part 2

**Files:**
- Create: `spire-e2e/src/test/resources/fixtures/chain/src/main/java/e2e/Defects.java`
- Create: `spire-e2e/src/test/resources/fixtures/chain/src/ui/defects.ts`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/Fixtures.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/Environment.java`
- Test: `spire-e2e/src/test/java/dev/codespire/e2e/EnvironmentTest.java`

**Interfaces:**
- Consumes: `GitLabDriver` (Task 5), `SpireDriver` (Task 6), `Stack` (Task 2).
- Produces:
  - `Fixtures.read(String resourcePath)` → `String`.
  - `Environment.provision(String projectPrefix)` → `Environment`, holding `projectId()`, `projectPath()`, `workspace()`, `slug()`, `bot()`, `human()` (each a `GitLabDriver`), and `spire()`.
  - `Environment.BOT_USERNAME` = `"e2e-bot"`, `Environment.HUMAN_USERNAME` = `"e2e-human"`.

- [ ] **Step 1: Write the fixture files**

Create `spire-e2e/src/test/resources/fixtures/chain/src/main/java/e2e/Defects.java`. Line numbers matter — the mock's stub cites lines 7 and 12, so the markers must sit there.

```java
package e2e;

/** E2E fixture. Every defect here is deliberate and marked. */
public final class Defects {

    public static int divide(int numerator, int denominator) {
        return numerator / denominator;  // E2E-DEFECT-A
    }

    public static String at(String[] values, int index) {
        // E2E-DEFECT-B
        return values[index];
    }
}
```

Create `spire-e2e/src/test/resources/fixtures/chain/src/ui/defects.ts`:

```typescript
export interface Row {
  id: string;
}

export function unusedBinding(rows: Row[]): number {
  const unused = rows.length;  // E2E-DEFECT-C
  return rows.length;
}
```

- [ ] **Step 2: Write `Fixtures`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/Fixtures.java`:

```java
package dev.codespire.e2e.support;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Fixture repository contents, held as test resources so they are reviewable as real files. */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String resourcePath) {
        try (InputStream stream = Fixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("no fixture at " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read fixture " + resourcePath, e);
        }
    }
}
```

- [ ] **Step 3: Write the failing environment test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/EnvironmentTest.java`:

```java
package dev.codespire.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentTest {

    @Test
    void provisionsAProjectRegisteredWithBothSides() {
        Environment env = Environment.provision("e2e-env-probe");

        assertTrue(env.projectId() > 0);
        assertFalse(env.slug().isBlank());
        assertNotEquals(env.bot(), env.human(),
                "the self-loop guard means the bot must not answer its own comments — two users, "
                        + "and therefore two tokens");

        // The webhook is registered on both sides; a delivery attempt proves the routing key resolves.
        assertTrue(env.webhookKey().length() > 8);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :spire-e2e:test --tests '*EnvironmentTest*'`
Expected: FAIL to compile — `Environment` does not exist.

- [ ] **Step 5: Write `Environment`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/Environment.java`:

```java
package dev.codespire.e2e;

import dev.codespire.e2e.gitlab.GitLabDriver;
import dev.codespire.e2e.spire.SpireDriver;
import dev.codespire.e2e.support.Stack;

/**
 * The setup phase, and itself a test: if provider or webhook registration regresses, nothing
 * downstream can start, and this is where that shows.
 *
 * <p>Ordered, and each step asserts its own result rather than assuming the call succeeded. GitLab's
 * outbound guard in particular fails silently from our side — the symptom is the bot going quiet,
 * which is indistinguishable from a policy decline.
 */
public final class Environment {

    public static final String BOT_USERNAME = "e2e-bot";

    public static final String HUMAN_USERNAME = "e2e-human";

    private static final String BOT_TOKEN = "TEST-e2e-bot-token-0000000000";

    private static final String HUMAN_TOKEN = "TEST-e2e-human-token-00000000";

    /** GitLab's own name for the namespace; our review ids call it the workspace. */
    private final String workspace;

    private final String slug;

    private final long projectId;

    private final String webhookKey;

    private final GitLabDriver bot;

    private final GitLabDriver human;

    private final SpireDriver spire;

    private Environment(String workspace, String slug, long projectId, String webhookKey,
                        GitLabDriver bot, GitLabDriver human, SpireDriver spire) {
        this.workspace = workspace;
        this.slug = slug;
        this.projectId = projectId;
        this.webhookKey = webhookKey;
        this.bot = bot;
        this.human = human;
        this.spire = spire;
    }

    public static Environment provision(String projectPrefix) {
        Stack.requireUp();

        GitLabDriver root = GitLabDriver.asRoot();

        // Runs before anything else creates a hook: a registration made while this is off is refused
        // at GitLab's end, and nothing on our side records the refusal.
        root.allowLocalWebhooks();
        if (!root.get("/application/settings")
                .get("allow_local_requests_from_web_hooks_and_services").asBoolean()) {
            throw new IllegalStateException("GitLab still refuses private-network webhook targets. "
                    + "Every delivery would be dropped at its end with no trace on ours.");
        }

        long botId = root.ensureUser(BOT_USERNAME, BOT_USERNAME + "@example.invalid", "TEST-bot-pw-123");
        long humanId = root.ensureUser(HUMAN_USERNAME, HUMAN_USERNAME + "@example.invalid", "TEST-human-pw-123");
        root.mintToken(BOT_USERNAME, BOT_TOKEN);
        root.mintToken(HUMAN_USERNAME, HUMAN_TOKEN);

        // Older runs that crashed before their own cleanup, plus this run's predecessors.
        root.deleteProjectsNamed(projectPrefix);

        String slug = projectPrefix + "-" + System.currentTimeMillis();
        long projectId = root.createProject(slug);
        root.addMember(projectId, botId);
        root.addMember(projectId, humanId);

        String workspace = root.get("/projects/" + projectId).get("namespace").get("path").asText();

        SpireDriver spire = new SpireDriver();
        spire.registerScmProvider("e2e-gitlab", "http://gitlab/api/v4", workspace, BOT_TOKEN);
        spire.catalogueUnmeteredModel("e2e-mock-model", "E2E mock model");
        spire.registerLlmProvider("e2e-llm-mock", "http://llm-mock:8080/v1", "e2e-mock-model");

        SpireDriver.Webhook hook = spire.registerWebhook("gitlab", workspace + "/" + slug);
        // The service name and container port, not the published host port: GitLab reaches the
        // dashboard's nginx across the compose network, and nginx is what routes /webhooks.
        root.createWebhook(projectId, "http://ui:8080/webhooks/gitlab/" + hook.key(), hook.secret());

        spire.setReviewMode("active");

        return new Environment(workspace, slug, projectId, hook.key(),
                GitLabDriver.as(BOT_TOKEN), GitLabDriver.as(HUMAN_TOKEN), spire);
    }

    public String workspace() {
        return workspace;
    }

    public String slug() {
        return slug;
    }

    public long projectId() {
        return projectId;
    }

    public String webhookKey() {
        return webhookKey;
    }

    public GitLabDriver bot() {
        return bot;
    }

    public GitLabDriver human() {
        return human;
    }

    public SpireDriver spire() {
        return spire;
    }

    public String reviewId(long mrIid) {
        return "review::" + workspace + "/" + slug + "#" + mrIid;
    }
}
```

- [ ] **Step 6: Run it to verify it passes**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*EnvironmentTest*'
```
Expected: PASS.

If `registerScmProvider` returns 400 with `baseUrl must resolve to a public address`, the overlay's `SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS` did not reach the orchestrator — re-run Task 3 Step 5.

- [ ] **Step 7: Commit**

```bash
git add spire-e2e/src/test/resources/fixtures spire-e2e/src/test/java/dev/codespire/e2e
git commit -m "Add the e2e fixture repository and setup phase"
```

---

### Task 9: S1 — the walking skeleton closes

The first scenario. When this passes, every hard integration question is answered: GitLab boots, the webhook reaches nginx and the gateway, the SSRF relaxation holds, auth works, and the mock's wire format is accepted by `spire-llm`.

**Files:**
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java`
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/support/ReadModel.java`

**Interfaces:**
- Consumes: `Environment` (Task 8), `Await`, `Psql` (Task 4), `LlmMock` (Task 6).
- Produces:
  - `ReadModel.status(String reviewId)` → `String`.
  - `ReadModel.threads(String reviewId)` → `List<ReadModel.Thread>`, a `record Thread(String threadRef, String line, boolean isOurs, boolean isSummary, boolean resolved, int turnCount, String rootRef)`.
  - `ReadModel.events(String reviewId, String type)` → `long` count.
  - `ReadModel.findingCount(String reviewId)` → `long`.

- [ ] **Step 1: Write `ReadModel`**

Create `spire-e2e/src/test/java/dev/codespire/e2e/support/ReadModel.java`:

```java
package dev.codespire.e2e.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed reads over the orchestrator's read model.
 *
 * <p>Half of every assertion in this suite. The other half asks GitLab, because this side says what we
 * BELIEVE happened: a resolve that degraded to reply-only writes ThreadReplied rather than
 * ThreadResolved here, and only GitLab can say whether the thread is actually resolved. Asserting one
 * without the other is how a fake `resolved:true` survived in the GitHub adapter.
 */
public final class ReadModel {

    public record Thread(String threadRef, String line, boolean isOurs, boolean isSummary,
                         boolean resolved, int turnCount, String rootRef) {
    }

    private ReadModel() {
    }

    public static String status(String reviewId) {
        return Psql.one("SELECT status FROM orchestrator.review_status WHERE review_id = "
                + quote(reviewId));
    }

    public static long findingCount(String reviewId) {
        return Long.parseLong(Psql.one(
                "SELECT count(*) FROM orchestrator.review_finding WHERE review_id = " + quote(reviewId)));
    }

    public static long events(String reviewId, String type) {
        return Long.parseLong(Psql.one("SELECT count(*) FROM orchestrator.review_event WHERE review_id = "
                + quote(reviewId) + " AND type = " + quote(type)));
    }

    public static List<Thread> threads(String reviewId) {
        List<Thread> threads = new ArrayList<>();
        for (List<String> row : Psql.rows(
                "SELECT thread_ref, coalesce(line::text, ''), is_ours, is_summary, resolved, "
                        + "turn_count, coalesce(root_ref, '') FROM orchestrator.review_thread "
                        + "WHERE review_id = " + quote(reviewId) + " ORDER BY seq")) {
            threads.add(new Thread(row.get(0), row.get(1), "t".equals(row.get(2)), "t".equals(row.get(3)),
                    "t".equals(row.get(4)), Integer.parseInt(row.get(5)), row.get(6)));
        }
        return threads;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
```

- [ ] **Step 2: Write the failing S1 test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java`:

```java
package dev.codespire.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MR 1: Mode G's S1-S11, in Mode G's order.
 *
 * <p>ONE ORDERED CHAIN, not twelve independent tests — S5 needs S4's turns, S9 needs S1's findings. A
 * break in S1 reddens everything after it, which is accepted because the alternative is not testing
 * the conversation at all. The mitigations are that every failure message leads with the step name,
 * and that the two riskiest concerns (per-language context, the rename) live in their own MRs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReviewChainTest {

    private static Environment env;

    private static long mrIid;

    private static String reviewId;

    @BeforeAll
    static void openTheMergeRequest() {
        LlmMock.reset();
        env = Environment.provision("e2e-chain");

        env.bot().commit(env.projectId(), "main", null, "Add the fixture sources", List.of(
                FileAction.create("src/main/java/e2e/Defects.java",
                        Fixtures.read("fixtures/chain/src/main/java/e2e/Defects.java")),
                FileAction.create("src/ui/defects.ts",
                        Fixtures.read("fixtures/chain/src/ui/defects.ts"))));

        // The MR's own change: re-touch both files so the diff carries every marker as an ADDED line,
        // which is what the mock keys on.
        env.human().commit(env.projectId(), "e2e-topic", "main", "Introduce the marked defects", List.of(
                FileAction.update("src/main/java/e2e/Defects.java",
                        Fixtures.read("fixtures/chain/src/main/java/e2e/Defects.java")
                                .replace("/** E2E fixture.", "/** E2E fixture (changed).")),
                FileAction.update("src/ui/defects.ts",
                        Fixtures.read("fixtures/chain/src/ui/defects.ts")
                                .replace("export interface Row {", "export interface Row { // changed"))));

        mrIid = env.human().openMergeRequest(env.projectId(), "e2e-topic", "main", "E2E chain");
        reviewId = env.reviewId(mrIid);
    }

    @Test
    @Order(1)
    void s1_theReviewPostsOneInlineCommentPerFindingAndOneSummary() {
        Await.until("S1 review completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        long findings = ReadModel.findingCount(reviewId);
        assertEquals(3, findings, "S1 — the mock returns three findings for this fixture");

        // Our side.
        List<ReadModel.Thread> ours = ReadModel.threads(reviewId).stream()
                .filter(ReadModel.Thread::isOurs).toList();
        assertEquals(1, ours.stream().filter(ReadModel.Thread::isSummary).count(),
                "S1 — exactly one summary comment");

        // GitLab's side. The read model saying we posted is not proof that GitLab has it.
        JsonNode discussions = env.human().discussions(env.projectId(), mrIid);
        long botDiscussions = 0;
        for (JsonNode discussion : discussions) {
            JsonNode firstNote = discussion.get("notes").get(0);
            if (Environment.BOT_USERNAME.equals(firstNote.get("author").get("username").asText())) {
                botDiscussions++;
            }
        }
        assertEquals(4, botDiscussions,
                "S1 — three inline findings plus one summary, present on GitLab itself");

        assertTrue(LlmMock.prompts().size() >= 1, "S1 — the worker actually called the model");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*ReviewChainTest*'
```
Expected: FAIL — most likely `S1 review completed — not satisfied within PT4M`. That is the correct first failure; work the diagnosis below until it passes.

- [ ] **Step 4: Diagnose using the runbook's own order**

When nothing arrives, check the plumbing before the policy — the same order Mode G prescribes:

```bash
# 1. Did GitLab even try? Its own delivery log is the only record when the target refused.
curl -s -H "PRIVATE-TOKEN: TEST-e2e-root-token-000000000000" \
  "http://localhost:34780/api/v4/projects/<id>/hooks" | head -40

# 2. Did the gateway reject the signature?
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env \
  logs gateway --since 10m | grep -iE "Rejected|webhook"

# 3. Did the worker call the model?
curl -s http://localhost:34781/__admin/requests | head -60

# 4. Only then read the orchestrator's decision.
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env \
  logs orchestrator --since 10m | grep -iE "review|declined|skipped"
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :spire-e2e:test --tests '*ReviewChainTest*'`
Expected: PASS.

**This is the milestone.** Everything after it is additive.

- [ ] **Step 6: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e
git commit -m "Assert the first end-to-end review against a real GitLab"
```

---

### Task 10: S2–S8 — the conversation scenarios

**Files:**
- Modify: `spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java`

**Interfaces:**
- Consumes: everything from Task 9.
- Produces: no new types; adds ordered methods `s3_`, `s2_`, `s4_`, `s5_`, `s6_`, `s7_`, `s8_`.

Method order follows Mode G, which runs S3 before S2 on purpose: there is nothing to expand until a conversation exists.

- [ ] **Step 1: Write S3 and S2**

Append to `ReviewChainTest`:

```java
    private String firstFindingDiscussionId() {
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            JsonNode note = discussion.get("notes").get(0);
            boolean ours = Environment.BOT_USERNAME.equals(note.get("author").get("username").asText());
            if (ours && !note.get("position").isNull()) {
                return discussion.get("id").asText();
            }
        }
        throw new AssertionError("no inline finding discussion found on the merge request");
    }

    @Test
    @Order(2)
    void s3_aReplyUnderAFindingIsAnsweredInThatThreadWithFencedCode() {
        String discussionId = firstFindingDiscussionId();
        long before = countBotNotes(discussionId);

        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                "Why is this a problem when the denominator is validated upstream?");

        String answer = Await.until("S3 bot answered in the finding thread", () -> {
            for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
                if (!discussionId.equals(discussion.get("id").asText())) {
                    continue;
                }
                for (JsonNode note : discussion.get("notes")) {
                    if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())
                            && note.get("body").asText().contains("E2E fixture reply")) {
                        return Optional.of(note.get("body").asText());
                    }
                }
            }
            return Optional.empty();
        });

        assertTrue(countBotNotes(discussionId) > before, "S3 — the answer landed in THIS thread");
        assertTrue(answer.contains("```"),
                "S3 — the locked FOLLOWUP contract requires a fence; indented code renders as prose "
                        + "on the SCM: " + answer);
    }

    private long countBotNotes(String discussionId) {
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            if (discussionId.equals(discussion.get("id").asText())) {
                long count = 0;
                for (JsonNode note : discussion.get("notes")) {
                    if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())) {
                        count++;
                    }
                }
                return count;
            }
        }
        return 0;
    }

    @Test
    @Order(3)
    void s2_theThreadEndpointReturnsFullTextNotThePreview() {
        String discussionId = firstFindingDiscussionId();
        JsonNode thread = env.spire().get("/api/reviews/" + env.workspace() + "/" + env.slug()
                + "/" + mrIid + "/threads/" + discussionId);

        String text = thread.toString();
        assertTrue(text.length() > 160,
                "S2 — the card used to show only the <=160-char preview; the endpoint must return the "
                        + "full conversation: " + text);
        assertTrue(text.contains("E2E fixture reply"), "S2 — the bot's answer is in the returned thread");
    }
```

- [ ] **Step 2: Write S4 and S5**

```java
    @Test
    @Order(4)
    void s4_repliesToTheBotsOwnAnswerStayInOneConversation() {
        String discussionId = firstFindingDiscussionId();

        for (int turn = 1; turn <= 2; turn++) {
            long before = countBotNotes(discussionId);
            env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                    "Follow-up question number " + turn + "?");
            long expected = before + 1;
            Await.until("S4 bot answered turn " + turn,
                    () -> countBotNotes(discussionId) >= expected ? Optional.of(true) : Optional.empty());
        }

        // Turns accumulate on the conversation ROOT. A per-answer row that reset the count would let
        // the cap never fire, which is how multi-turn conversations died on Bitbucket.
        List<ReadModel.Thread> roots = ReadModel.threads(reviewId).stream()
                .filter(thread -> thread.turnCount() >= 3)
                .toList();
        assertEquals(1, roots.size(),
                "S4 — one conversation root carrying every turn, not one row per answer: "
                        + ReadModel.threads(reviewId));
    }

    @Test
    @Order(5)
    void s5_theTurnCapPostsExactlyOneNoticeAndAMentionOverridesIt() {
        String discussionId = firstFindingDiscussionId();

        Await.until("S5 turn cap reached", () -> {
            env.human().replyToDiscussion(env.projectId(), mrIid, discussionId, "And another question?");
            return ReadModel.events(reviewId, "TurnCapNotified") >= 1 ? Optional.of(true) : Optional.empty();
        });

        long noticesAfterCap = ReadModel.events(reviewId, "TurnCapNotified");
        long answersBefore = countBotNotes(discussionId);

        // One more plain reply must post NOTHING — the notice is once per thread.
        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId, "One more plain reply.");
        Await.absent("S5 no second notice after the cap",
                () -> ReadModel.events(reviewId, "TurnCapNotified"));
        assertEquals(noticesAfterCap, ReadModel.events(reviewId, "TurnCapNotified"));

        // An @-mention overrides the cap and gets a real answer.
        env.human().replyToDiscussion(env.projectId(), mrIid, discussionId,
                "@" + Environment.BOT_USERNAME + " please answer this one.");
        Await.until("S5 mention overrides the cap",
                () -> countBotNotes(discussionId) > answersBefore ? Optional.of(true) : Optional.empty());
    }
```

- [ ] **Step 3: Write S6, S7 and S8**

```java
    @Test
    @Order(6)
    void s6_aMentionOnAnUnflaggedLineIsAnsweredAndCreatesNoFinding() {
        long findingsBefore = ReadModel.findingCount(reviewId);

        env.human().createDiscussionOnLine(env.projectId(), mrIid,
                "src/main/java/e2e/Defects.java", 1,
                "@" + Environment.BOT_USERNAME + " what does this package do?");

        Await.until("S6 bot answered an unflagged line", () -> {
            for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
                for (JsonNode note : discussion.get("notes")) {
                    if (Environment.BOT_USERNAME.equals(note.get("author").get("username").asText())
                            && note.get("body").asText().contains("E2E fixture reply")
                            && discussion.get("notes").get(0).get("body").asText().contains("what does this package do")) {
                        return Optional.of(true);
                    }
                }
            }
            return Optional.empty();
        });

        assertEquals(findingsBefore, ReadModel.findingCount(reviewId),
                "S6 — answering a mention must not create a finding");
    }

    @Test
    @Order(7)
    void s7_aPlainMergeRequestCommentIsAnsweredInTheSummaryThread() {
        String summaryRef = ReadModel.threads(reviewId).stream()
                .filter(ReadModel.Thread::isSummary)
                .findFirst()
                .orElseThrow(() -> new AssertionError("S7 — no summary thread recorded"))
                .threadRef();

        env.human().addNote(env.projectId(), mrIid, "Is this change safe to merge on a Friday?");

        Await.until("S7 answered in the summary thread", () -> {
            for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
                if (!summaryRef.equals(discussion.get("id").asText())) {
                    continue;
                }
                for (JsonNode note : discussion.get("notes")) {
                    if (note.get("body").asText().contains("E2E fixture reply")) {
                        return Optional.of(true);
                    }
                }
            }
            return Optional.empty();
        });
    }

    @Test
    @Order(8)
    void s8_slashReviewUpdatesTheSummaryInPlace() {
        long summariesBefore = countSummaryDiscussions();

        env.human().addNote(env.projectId(), mrIid, "/review");

        Await.until("S8 a second run completed", () -> {
            long runs = ReadModel.events(reviewId, "ReviewRequested");
            return runs >= 2 && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        assertEquals(summariesBefore, countSummaryDiscussions(),
                "S8 — the summary comment is updated in place, never duplicated");
    }

    private long countSummaryDiscussions() {
        String summaryRef = ReadModel.threads(reviewId).stream()
                .filter(ReadModel.Thread::isSummary)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no summary thread recorded"))
                .threadRef();
        long count = 0;
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            if (summaryRef.equals(discussion.get("id").asText())) {
                count++;
            }
        }
        return count;
    }
```

- [ ] **Step 4: Run the chain**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*ReviewChainTest*'
```
Expected: PASS, 8 ordered tests.

- [ ] **Step 5: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java
git commit -m "Assert the conversation scenarios end to end"
```

---

### Task 11: S9a, S9b, S10 and S11 — reconciliation and close

S9b is the load-bearing assertion of the whole suite. The first draft of the design asserted that untouched findings stay `UNCHANGED`, which is true with or without the GitLab compare-diff regression — when the compare diff parses to zero files, *every* file reads as untouched, so untouched findings still read `UNCHANGED` and the assertion passes. Only a **touched-but-unfixed** finding surviving as `STILL_OPEN` can fail under that regression.

**Files:**
- Modify: `spire-e2e/src/test/java/dev/codespire/e2e/ReviewChainTest.java`
- Modify: `spire-e2e/src/test/java/dev/codespire/e2e/support/ReadModel.java`

**Interfaces:**
- Produces: `ReadModel.verdicts(String reviewId)` → `List<ReadModel.Verdict>`, a `record Verdict(String path, String line, String status)`.

- [ ] **Step 1: Add the verdict read**

Append to `ReadModel`:

```java
    public record Verdict(String path, String line, String status) {
    }

    /**
     * Verdicts as the read model recorded them. The reconcile call decides these — our code only
     * downgrades an untouched STILL_OPEN to UNCHANGED — so a wrong value here is either the model's
     * answer or the downgrade, and the fixture pins the model's answer.
     */
    public static List<Verdict> verdicts(String reviewId) {
        List<Verdict> verdicts = new ArrayList<>();
        for (List<String> row : Psql.rows(
                "SELECT path, coalesce(line::text, ''), coalesce(verdict, '') "
                        + "FROM orchestrator.review_finding WHERE review_id = " + quote(reviewId)
                        + " ORDER BY path, line")) {
            verdicts.add(new Verdict(row.get(0), row.get(1), row.get(2)));
        }
        return verdicts;
    }
```

- [ ] **Step 2: Write S9a and S9b**

Append to `ReviewChainTest`:

```java
    @Test
    @Order(9)
    void s9_aFullFixResolvesAndAPartialFixStaysOpen() {
        // Defect A fully removed; defect B PARTIALLY fixed — the file is touched at B's hunk but the
        // problem remains. The partial fix is the point: an untouched finding reads UNCHANGED whether
        // or not the compare diff parsed, so only this case can fail under that regression.
        env.human().commit(env.projectId(), "e2e-topic", null, "Fix A fully, B partially", List.of(
                FileAction.update("src/main/java/e2e/Defects.java", """
                        package e2e;

                        /** E2E fixture (changed). */
                        public final class Defects {

                            public static int divide(int numerator, int denominator) {
                                return denominator == 0 ? 0 : numerator / denominator;
                            }

                            public static String at(String[] values, int index) {
                                // E2E-DEFECT-B
                                if (index >= values.length) {
                                    return null;
                                }
                                return values[index];
                            }
                        }
                        """)));

        Await.until("S9 the reconciling run completed", () -> {
            long runs = ReadModel.events(reviewId, "ReviewRequested");
            return runs >= 3 && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        List<ReadModel.Verdict> verdicts = ReadModel.verdicts(reviewId);

        assertTrue(verdicts.stream().anyMatch(v -> "RESOLVED".equals(v.status())),
                "S9a — the fully fixed finding resolves: " + verdicts);

        assertTrue(verdicts.stream().anyMatch(v -> "STILL_OPEN".equals(v.status())),
                "S9b — a TOUCHED but unfixed finding must survive as STILL_OPEN. If this reads "
                        + "UNCHANGED, the incremental diff parsed to zero files and downgradeUntouched "
                        + "rewrote it — the exact regression this suite exists to catch: " + verdicts);

        // GitLab's side: a RESOLVED finding's thread is really resolved, not merely replied to.
        assertTrue(ReadModel.events(reviewId, "ThreadResolved") >= 1,
                "S9a — ThreadResolved is written only when the adapter reported resolved:true, so its "
                        + "presence is proof the SCM-side resolve landed rather than degrading to a reply");
        assertTrue(anyDiscussionResolvedOnGitLab(),
                "S9a — and GitLab itself must agree the thread is resolved");
    }

    private boolean anyDiscussionResolvedOnGitLab() {
        for (JsonNode discussion : env.human().discussions(env.projectId(), mrIid)) {
            for (JsonNode note : discussion.get("notes")) {
                if (note.has("resolved") && note.get("resolved").asBoolean()) {
                    return true;
                }
            }
        }
        return false;
    }
```

- [ ] **Step 3: Write S10 and S11**

```java
    @Test
    @Order(10)
    void s10_theRemainingFixesCloseOutAndANewDefectIsRaised() {
        env.human().commit(env.projectId(), "e2e-topic", null, "Fix B and C, add D", List.of(
                FileAction.update("src/main/java/e2e/Defects.java", """
                        package e2e;

                        /** E2E fixture (changed). */
                        public final class Defects {

                            public static int divide(int numerator, int denominator) {
                                return denominator == 0 ? 0 : numerator / denominator;
                            }

                            public static String at(String[] values, int index) {
                                if (index < 0 || index >= values.length) {
                                    return null;
                                }
                                return values[index];
                            }
                        }
                        """),
                FileAction.update("src/ui/defects.ts", """
                        export interface Row { // changed
                          id: string;
                        }

                        export function count(rows: Row[]): number {
                          return rows.length;
                        }

                        export function widened(rows: any): number {  // E2E-DEFECT-A
                          return rows.length;
                        }
                        """)));

        Await.until("S10 the closing run completed", () -> {
            long runs = ReadModel.events(reviewId, "ReviewRequested");
            return runs >= 4 && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        JsonNode summary = env.spire().reviewSummary(env.workspace(), env.slug(), mrIid);
        assertEquals(0, summary.get("openBlockers").asInt(),
                "S10 — every blocker is closed by the end of the chain: " + summary);
    }

    @Test
    @Order(11)
    void s11_mergingFlipsTheBadgeAndStartsNoNewWork() {
        env.human().mergeMergeRequest(env.projectId(), mrIid);

        // The POSITIVE signal first. Asserting the absence immediately would pass against a system
        // that simply has not processed the merge yet, which is not an assertion.
        Await.until("S11 the PR badge flips to MERGED", () -> {
            JsonNode summary = env.spire().reviewSummary(env.workspace(), env.slug(), mrIid);
            return "MERGED".equals(summary.get("prState").asText()) ? Optional.of(true) : Optional.empty();
        });

        assertEquals("completed", ReadModel.status(reviewId),
                "S11 — the PR state is its own axis; merging does not change the review status");

        Await.absent("S11 no new review run after the merge",
                () -> ReadModel.events(reviewId, "ReviewRequested"));
    }
```

- [ ] **Step 4: Run the full chain**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*ReviewChainTest*'
```
Expected: PASS, 11 ordered tests.

- [ ] **Step 5: Prove S9b discriminates**

The assertion that matters most must be shown to be capable of failing. Temporarily change the reconcile fixture's second verdict from `still-open` to `unchanged`, reset the mappings, and re-run only the chain.

Expected: `s9_aFullFixResolvesAndAPartialFixStaysOpen` FAILS on the S9b assertion, and S9a still passes.

Revert the fixture, reset the mappings, and confirm green. This mirrors the mutation discipline the repo already applies to its guards: break the production line, confirm exactly one test fails.

- [ ] **Step 6: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e
git commit -m "Assert reconciliation verdicts and merge close-out end to end"
```

---

### Task 12: The code-context probes (MRs 2 and 3)

**Files:**
- Create: `spire-e2e/src/test/resources/fixtures/probe-java/` (three files, below)
- Create: `spire-e2e/src/test/resources/fixtures/probe-ts/` (three files, below)
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/CodeContextProbeTest.java`

**Interfaces:**
- Consumes: `Environment`, `LlmMock`, `Await`, `ReadModel`.
- Produces: nothing consumed by later tasks.

Asserting on the **prompt** rather than on the findings is the point: a finding is the model's opinion and unassertable, while the prompt is a fact about our code.

- [ ] **Step 1: Write the Java probe fixture**

`spire-e2e/src/test/resources/fixtures/probe-java/Pricer.java` — the definition:

```java
package e2e.probe;

/** E2E probe definition. Its body is what the code-context provider must retrieve. */
public final class Pricer {

    public static final String MARKER = "E2E-PROBE-DEFINITION-BODY";

    public static long chargeFor(long tokens) {
        return tokens * 2;
    }
}
```

`spire-e2e/src/test/resources/fixtures/probe-java/Changed.java` — the file the MR touches, importing the definition:

```java
package e2e.probe;

import e2e.probe.Pricer;

/** E2E probe: the changed file. Its import is what rung 1 resolves. */
public final class Changed {

    public long total(long tokens) {
        return Pricer.chargeFor(tokens);
    }
}
```

`spire-e2e/src/test/resources/fixtures/probe-java/Caller.java` — a file that references the changed file's symbol, so rung 2 has a caller to find:

```java
package e2e.probe;

import e2e.probe.Changed;

/** E2E probe: an existing caller. Rung 2 must name this file. */
public final class Caller {

    public long invoke() {
        return new Changed().total(10L);
    }
}
```

- [ ] **Step 2: Write the TypeScript probe fixture**

`spire-e2e/src/test/resources/fixtures/probe-ts/pricer.ts`:

```typescript
export const MARKER = 'E2E-PROBE-DEFINITION-BODY';

export function chargeFor(tokens: number): number {
  return tokens * 2;
}
```

`spire-e2e/src/test/resources/fixtures/probe-ts/changed.ts`:

```typescript
import { chargeFor } from './pricer';

export function total(tokens: number): number {
  return chargeFor(tokens);
}
```

`spire-e2e/src/test/resources/fixtures/probe-ts/caller.ts`:

```typescript
import { total } from './changed';

export function invoke(): number {
  return total(10);
}
```

- [ ] **Step 3: Write the probe test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/CodeContextProbeTest.java`:

```java
package dev.codespire.e2e;

import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MRs 2 and 3: the code-context path, one MR per language.
 *
 * <p>The review loop does not branch on language, so running the whole chain twice would test the
 * same code twice. What genuinely differs per language is import parsing, the symbol index and caller
 * lookup — plus the two independently-maintained extension maps
 * (techdebt/global/3-2-code-extension-map-duplicated-with-no-drift-guard.md).
 *
 * <p>Both probes assert against the PROMPT, read from llm-mock's request journal. A finding is the
 * model's opinion and unassertable; the prompt is a fact about our code.
 */
class CodeContextProbeTest {

    @Test
    void aJavaDefinitionAndItsCallerReachTheModel() {
        runProbe("e2e-probe-java", "src/main/java/e2e/probe",
                List.of("Pricer.java", "Changed.java", "Caller.java"), "probe-java", ".java",
                "public long total(long tokens) {\n        return Pricer.chargeFor(tokens) + 1;\n    }");
    }

    @Test
    void aTypeScriptDefinitionAndItsCallerReachTheModel() {
        runProbe("e2e-probe-ts", "src/ui/probe",
                List.of("pricer.ts", "changed.ts", "caller.ts"), "probe-ts", ".ts",
                "export function total(tokens: number): number {\n  return chargeFor(tokens) + 1;\n}");
    }

    /**
     * Two reviews, not one. The symbol index only knows files that reviews have READ, so rung 2 has
     * nothing to offer on a first review — asserting the caller citation there would fail for a
     * correct implementation. The first push populates; the second is the one under test.
     */
    private void runProbe(String prefix, String directory, List<String> files,
                          String fixtureDir, String extension, String secondEdit) {
        LlmMock.reset();
        Environment env = Environment.provision(prefix);

        List<FileAction> seed = files.stream()
                .map(name -> FileAction.create(directory + "/" + name,
                        Fixtures.read("fixtures/" + fixtureDir + "/" + name)))
                .toList();
        env.bot().commit(env.projectId(), "main", null, "Add the probe sources", seed);

        String changedPath = directory + "/" + (extension.equals(".java") ? "Changed.java" : "changed.ts");
        String original = Fixtures.read("fixtures/" + fixtureDir + "/"
                + (extension.equals(".java") ? "Changed.java" : "changed.ts"));

        // Round 1 — populates the symbol index.
        env.human().commit(env.projectId(), "e2e-probe", "main", "Touch the changed file",
                List.of(FileAction.update(changedPath, original + "\n")));
        long iid = env.human().openMergeRequest(env.projectId(), "e2e-probe", "main", "E2E context probe");
        String reviewId = env.reviewId(iid);

        Await.until("probe round 1 completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        // Round 2 — the one under test.
        env.human().commit(env.projectId(), "e2e-probe", null, "Change the call site",
                List.of(FileAction.update(changedPath, original.replace(
                        extension.equals(".java")
                                ? "        return Pricer.chargeFor(tokens);"
                                : "  return chargeFor(tokens);",
                        secondEdit.lines().skip(1).findFirst().orElseThrow()))));

        Await.until("probe round 2 completed", () -> {
            long runs = ReadModel.events(reviewId, "ReviewRequested");
            return runs >= 2 && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        String prompts = String.join("\n\n", LlmMock.prompts());

        assertTrue(prompts.contains("E2E-PROBE-DEFINITION-BODY"),
                "rung 1 — the changed file's import must resolve to the definition, and the "
                        + "definition's body must reach the model");
        assertTrue(prompts.contains("Caller" + extension) || prompts.contains("caller" + extension),
                "rung 2 — the symbol index must name a real caller of the changed file's symbol. "
                        + "The index only knows files reviews have read, which is why this asserts on "
                        + "the SECOND review");
    }
}
```

- [ ] **Step 4: Run it to verify it fails, then passes**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*CodeContextProbeTest*'
```

If rung 1 fails, the code-context provider contributed nothing for this language — check both extension maps agree. If only rung 2 fails, the index populated but no caller was confirmed.

- [ ] **Step 5: Prove the probe discriminates**

A presence-only check against a large prompt can pass for the wrong reason. Temporarily rename `MARKER`'s value in `probe-java/Pricer.java` to something the assertion does not look for, and re-run.
Expected: `aJavaDefinitionAndItsCallerReachTheModel` FAILS on the rung 1 assertion.

Revert and confirm green.

- [ ] **Step 6: Commit**

```bash
git add spire-e2e/src/test/resources/fixtures spire-e2e/src/test/java/dev/codespire/e2e/CodeContextProbeTest.java
git commit -m "Assert code context reaches the model for Java and TypeScript"
```

---

### Task 13: The rename (MR 4)

**Files:**
- Create: `spire-e2e/src/test/java/dev/codespire/e2e/RenameTest.java`

**Interfaces:**
- Consumes: `Environment`, `Await`, `ReadModel`, `GitLabDriver.FileAction.move`.
- Produces: nothing.

**This assertion may fail on first implementation, and that failure is the deliverable.** `CLAUDE.md` records a 2026-07-26 pass where a 100%-similarity rename did **not** churn finding identity, while `SMOKE-TEST.md` calls the churn a known limitation and cites a `techdebt/` entry that does not exist. Nobody knows which is true. It lives in its own MR so a red rename cannot mask the chain, and it must **not** be marked `@Disabled` or expected-to-fail — a suppressed assertion restores exactly the state of not knowing that made it worth writing.

- [ ] **Step 1: Write the test**

Create `spire-e2e/src/test/java/dev/codespire/e2e/RenameTest.java`:

```java
package dev.codespire.e2e;

import dev.codespire.e2e.gitlab.GitLabDriver.FileAction;
import dev.codespire.e2e.spire.LlmMock;
import dev.codespire.e2e.support.Await;
import dev.codespire.e2e.support.Fixtures;
import dev.codespire.e2e.support.ReadModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MR 4: a 100%-similarity rename.
 *
 * <p>Mode G defers renames to its final round and the repository does not agree with itself about
 * why. This test decides it. If it goes red, that red is a reproduction of a defect nobody has
 * pinned down — NOT a broken test, and NOT something to disable.
 */
class RenameTest {

    private static final String ORIGINAL = "src/main/java/e2e/Defects.java";

    private static final String RENAMED = "src/main/java/e2e/Validation.java";

    @Test
    void findingsFollowARenamedFileAndResolveAtItsNewPath() {
        LlmMock.reset();
        Environment env = Environment.provision("e2e-rename");

        String source = Fixtures.read("fixtures/chain/src/main/java/e2e/Defects.java");
        env.bot().commit(env.projectId(), "main", null, "Add the fixture source",
                List.of(FileAction.create(ORIGINAL, source)));
        env.human().commit(env.projectId(), "e2e-rename-topic", "main", "Introduce the marked defects",
                List.of(FileAction.update(ORIGINAL,
                        source.replace("/** E2E fixture.", "/** E2E fixture (changed)."))));

        long iid = env.human().openMergeRequest(env.projectId(), "e2e-rename-topic", "main", "E2E rename");
        String reviewId = env.reviewId(iid);

        Await.until("rename: the first review completed", () ->
                "completed".equals(ReadModel.status(reviewId)) ? Optional.of(true) : Optional.empty());

        long findingsBefore = ReadModel.findingCount(reviewId);
        assertTrue(findingsBefore > 0, "rename: the first review must produce findings to follow");

        // A real move, in one commit. Delete-plus-create would be a 0%-similarity change and would
        // answer a different question entirely.
        env.human().commit(env.projectId(), "e2e-rename-topic", null, "Rename the file",
                List.of(FileAction.move(RENAMED, ORIGINAL,
                        source.replace("/** E2E fixture.", "/** E2E fixture (changed)."))));

        Await.until("rename: the second review completed", () -> {
            long runs = ReadModel.events(reviewId, "ReviewRequested");
            return runs >= 2 && "completed".equals(ReadModel.status(reviewId))
                    ? Optional.of(true) : Optional.empty();
        });

        List<ReadModel.Verdict> verdicts = ReadModel.verdicts(reviewId);

        assertTrue(verdicts.stream().anyMatch(verdict -> RENAMED.equals(verdict.path())),
                "the findings must FOLLOW the file to its new path: " + verdicts);

        assertTrue(verdicts.stream().noneMatch(verdict -> "SUPERSEDED".equals(verdict.status())),
                "SUPERSEDED means the finding's code disappeared. The code moved; it did not "
                        + "disappear: " + verdicts);

        assertTrue(ReadModel.findingCount(reviewId) <= findingsBefore,
                "a rename must not churn finding identity — the same defects must not come back as "
                        + "NEW findings at the new path. Found " + ReadModel.findingCount(reviewId)
                        + " after the rename, up from " + findingsBefore + ": " + verdicts);
    }
}
```

- [ ] **Step 2: Run it and record the answer**

```bash
set -a; . deploy/.env; set +a
./gradlew :spire-e2e:test --tests '*RenameTest*'
```

**Both outcomes are results.** If it passes, `SMOKE-TEST.md`'s "known limitation" paragraph is wrong and must be corrected, along with its dangling `techdebt/` citation. If it fails, file a tech-debt entry under `techdebt/` at the criticality the failure warrants, citing this test as the reproduction, and leave the test failing until the fix lands. Do **not** disable it.

- [ ] **Step 3: Reconcile the documentation either way**

Whichever way it went, `docs/SMOKE-TEST.md`'s Mode G prep paragraph currently cites a `techdebt/` entry that does not exist. Fix that citation as part of this task — either to the new entry, or by deleting the claim.

- [ ] **Step 4: Commit**

```bash
git add spire-e2e/src/test/java/dev/codespire/e2e/RenameTest.java docs/SMOKE-TEST.md
git commit -m "Assert findings follow a renamed file"
```

---

### Task 14: Diagnostics on failure

Without this, a nightly failure is a red square. Mode G's own troubleshooting greps are the specification for what to capture.

**Files:**
- Create: `deploy/e2e-diagnostics.sh`
- Modify: `spire-e2e/build.gradle.kts`

- [ ] **Step 1: Write the script**

Create `deploy/e2e-diagnostics.sh`:

```bash
#!/usr/bin/env bash
# Everything a failed e2e run needs and cannot reconstruct afterwards.
#
# When the bot goes silent, check the plumbing before the policy: a refused webhook and a legitimate
# policy decline look identical from our side, because nothing arrives and so nothing is logged. The
# only record of the former is at GitLab's end, which is why the hook list is captured here.
set -uo pipefail

OUT="${1:-e2e-diagnostics}"
COMPOSE="docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env"
mkdir -p "$OUT"

for service in gateway orchestrator worker ui gitlab llm-mock; do
    $COMPOSE logs "$service" --since 60m > "$OUT/$service.log" 2>&1
done

$COMPOSE ps > "$OUT/ps.txt" 2>&1

curl -s "http://localhost:${E2E_LLM_MOCK_PORT:-34781}/__admin/requests" \
    > "$OUT/llm-mock-journal.json" 2>&1

# GitLab's own delivery log. A rejected secret or an unreachable target shows ONLY here.
GITLAB="http://localhost:${E2E_GITLAB_PORT:-34780}"
TOKEN="TEST-e2e-root-token-000000000000"
for project in $(curl -s -H "PRIVATE-TOKEN: $TOKEN" "$GITLAB/api/v4/projects?owned=true&per_page=100" \
                 | grep -o '"id":[0-9]*' | cut -d: -f2); do
    curl -s -H "PRIVATE-TOKEN: $TOKEN" "$GITLAB/api/v4/projects/$project/hooks" \
        > "$OUT/gitlab-hooks-$project.json" 2>&1
done

echo "diagnostics written to $OUT"
```

```bash
chmod +x deploy/e2e-diagnostics.sh
```

- [ ] **Step 2: Run it on failure automatically**

Add to `spire-e2e/build.gradle.kts` inside `tasks.test`:

```kotlin
    // A nightly failure that captured nothing is a red square. Runs on failure only: a passing run
    // has nothing worth keeping, and writing 60 minutes of logs every time would bury the one run
    // that matters.
    doLast {
        if (state.failure != null) {
            providers.exec {
                workingDir = rootProject.projectDir
                commandLine("bash", "deploy/e2e-diagnostics.sh", "build/e2e-diagnostics")
            }.result.get()
        }
    }
```

- [ ] **Step 3: Verify it captures something**

Force a failure — stop the worker, run the chain, confirm the directory appears and `worker.log` is present:

```bash
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env stop worker
./gradlew :spire-e2e:test --tests '*ReviewChainTest*' || true
ls build/e2e-diagnostics/
docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml --env-file deploy/.env start worker
```
Expected: the directory lists `gateway.log`, `orchestrator.log`, `worker.log`, `ps.txt` and `llm-mock-journal.json`.

- [ ] **Step 4: Commit**

```bash
git add deploy/e2e-diagnostics.sh spire-e2e/build.gradle.kts
git commit -m "Capture logs and delivery history when an e2e run fails"
```

---

### Task 15: The nightly CI job

**Files:**
- Modify: `.github/workflows/e2e.yml`

- [ ] **Step 1: Add the job**

Append a second job to `.github/workflows/e2e.yml`. It builds from the checkout rather than pulling `:edge`, because the suite must test the code on the branch:

```yaml
  gitlab-loop:
    runs-on: ubuntu-latest
    # GitLab CE alone is a five-minute boot, and the chain runs four merge requests through real
    # webhook delivery and two model calls per round.
    timeout-minutes: 90
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
        with:
          distribution: temurin
          java-version: '25'
      - uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0

      - name: Generate keysets
        run: |
          echo "MASTER_KEYSET=$(./gradlew -q :spire-encryption:generateKeyset --console=plain | tail -1)" >> "$GITHUB_ENV"
          echo "WEBHOOK_KEYSET=$(./gradlew -q :spire-encryption:generateKeyset --console=plain | tail -1)" >> "$GITHUB_ENV"

      # Every value is obviously synthetic. No real credential enters CI.
      - name: Write the environment
        run: |
          cat > deploy/.env <<ENV
          SPIRE_UI_PORT=34700
          SPIRE_KEYCLOAK_PORT=34767
          POSTGRES_DB=spire
          POSTGRES_USER=spire
          POSTGRES_PASSWORD=TEST-postgres-password
          GATEWAY_POSTGRES_USER=gateway
          GATEWAY_POSTGRES_PASSWORD=TEST-gateway-password
          SPIRE_ENCRYPTION_KEYSET=${MASTER_KEYSET}
          SPIRE_ENCRYPTION_WEBHOOK_KEYSET=${WEBHOOK_KEYSET}
          SPIRE_OIDC_AUTH_SERVER_URL=http://host.docker.internal:34767/realms/spire
          SPIRE_OIDC_ORCHESTRATOR_SECRET=dev-only-orchestrator-secret
          SPIRE_OIDC_GATEWAY_SECRET=dev-only-gateway-secret
          SPIRE_OIDC_WORKER_SECRET=dev-only-worker-secret
          SPIRE_TRUSTED_PROXIES=172.16.0.0/12
          DEV_VIEWER_PASSWORD=TEST-viewer-password
          DEV_OPERATOR_PASSWORD=TEST-operator-password
          E2E_GITLAB_ROOT_PASSWORD=TEST-gitlab-root-password
          E2E_GITLAB_PORT=34780
          E2E_LLM_MOCK_PORT=34781
          ENV

      - name: Start the stack
        run: |
          docker compose -f deploy/compose.yml -f deploy/compose.e2e.yml \
            --env-file deploy/.env up -d --build

      - name: Wait for GitLab
        run: |
          for _ in $(seq 1 60); do
            curl -fsS http://localhost:34780/-/readiness >/dev/null 2>&1 && exit 0
            sleep 15
          done
          echo "GitLab never became ready"; exit 1

      - name: Run the suite
        run: |
          set -a; . deploy/.env; set +a
          ./gradlew testE2e --no-daemon --console=plain

      - name: Upload diagnostics
        if: failure()
        uses: actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4.6.2
        with:
          name: e2e-diagnostics
          path: spire-e2e/build/e2e-diagnostics
          if-no-files-found: warn
```

- [ ] **Step 2: Verify the workflow parses**

```bash
gh workflow view e2e.yml
```

- [ ] **Step 3: Trigger a manual run**

```bash
gh workflow run e2e.yml --ref feat/e2e-tests
```

Watch it, and read the uploaded diagnostics if it fails. Do not claim the job works until a run has passed — a workflow that parses is not a workflow that runs.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/e2e.yml
git commit -m "Run the GitLab e2e suite nightly"
```

---

## Self-Review

**Spec coverage.**

| Spec section | Task |
|---|---|
| §3.1 SSRF relaxation | Task 3 (set + verified in Step 5) |
| §3.2 GitLab outbound guard | Task 5 (driver), Task 8 (asserted in `provision`) |
| §3.4 two model calls | Task 7 (three call kinds), Task 11 (S9) |
| §4 topology | Task 3 |
| §4 mock as prompt observer | Task 6 (`LlmMock`), Task 12 (probes) |
| §5 setup phase | Task 8 |
| §6.1 locked-contract discriminator | Task 7 |
| §6.2 added-lines-only markers | Task 7 (Step 7 proves it discriminates) |
| §7.1 async contract | Task 4 (`Await`), used everywhere |
| §7.2 psql access | Task 4 (`Psql`) |
| §8 lifecycle, project purge | Task 2 (`Stack.requireUp`), Task 8 (`deleteProjectsNamed`) |
| §9.1 S1–S11 | Tasks 9, 10, 11 |
| §9.2 probes | Task 12 |
| §9.3 rename | Task 13 |
| §10 module, tier, licence | Tasks 1, 2 |
| §11 diagnostics, image pin, trim | Task 3 (pin, trim), Task 14 (diagnostics) |

Gap accepted and recorded: §12's JavaScript probe is not built. It is an open question in the spec, not a requirement.

**Placeholder scan.** No "TBD", no "similar to Task N", no "add error handling". Every code step carries the code.

**Type consistency.** `FileAction.create/update/delete/move`, `ReadModel.Thread`, `ReadModel.Verdict`, `SpireDriver.Webhook`, `Await.until/absent`, `Psql.one/rows`, `LlmMock.reset/prompts`, `Environment.provision/reviewId` are each defined once and used with the same signature everywhere after.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-30-gitlab-e2e-suite.md`.
