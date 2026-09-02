# Factory M0 — Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dispatched run clones a repository into a Docker sandbox, drives `codex exec` inside it, and pushes the agent's commit to a real remote as a dedicated machine account — refusing the push when the branch touches a CI-configuration file.

**Architecture:** One new deployable (`spire-run-worker`) consuming a new `cs.run-commands` topic, plus five new Apache-2.0 modules behind two seams (`HarnessAdapter`, `RunRuntime`). The orchestrator gains `POST /api/runs` and a `factory_run` read model. Nothing existing changes behaviour; the review pipeline is untouched.

**Tech Stack:** Java 25 · Quarkus 3.38.3 · Gradle Kotlin DSL · SmallRye Reactive Messaging (Kafka) · Postgres + Flyway · JGit · docker-java · JUnit 5 · Testcontainers (via Quarkus Dev Services)

**Spec:** [`docs/factory/`](../../factory/README.md) — PRD FR-F1..F4, F11, F13, F28, F29; ROADMAP §M0. Decisions ADR-029..ADR-038 in [`docs/DECISIONS.md`](../../DECISIONS.md).

---

## Revision — 2026-09-01, after the Codex spike (ADR-039)

A measured spike against the real CLI, and an operator objection to the storage model, changed this
plan's shape. **Read [`docs/factory/RUN-TOPOLOGY.md`](../../factory/RUN-TOPOLOGY.md) before starting.**

What changed, and where:

| Was | Is | Tasks |
|---|---|---|
| Worker clones and bind-mounts a workspace | **The pod clones itself**; the worker holds no filesystem and runs no git | 3, 6, 8 |
| One handoff at the end of the run | **Continuous checkpointing** — every commit gated and pushed within seconds | 6b, 8 |
| `codex … --ask-for-approval never` | **That flag does not exist** in 0.152.0 | 2 |
| Sandbox mode `workspace-write` | **`danger-full-access`** — Codex's bubblewrap sandbox cannot initialize under Docker's default seccomp, and Codex does not fail fast when it can't | 2, 6 |
| NDJSON `agent_reasoning` / `exec_command_begin` | **`{"type":"item.completed","item":{…}}`** and `{"type":"error","message":…}` | 2 |
| `LandlockProbe` | **deleted** — it measured a primitive Codex does not use | 6 |
| Gate runs in the worker | **Gate runs in the publisher sidecar**, in its own clean clone | 4, 6b |
| — | **New Task 6b: `spire-publisher`**, the sidecar image that gates and pushes | 6b |

Two facts the spike established that the plan now depends on: **`ca-certificates` is mandatory** in
the agent image (without it every TLS call dies `UnknownIssuer` and Codex retries silently), and
**Codex genuinely works in a container** — verified end to end, editing a file and committing, with
the commit authored by the identity the workspace was configured with.

## Global Constraints

Every task's requirements implicitly include this section.

**Adding a module is a four-file ritual. Three build guards fail otherwise, and they are not advisory:**

1. `settings.gradle.kts` — `include("spire-<name>")`
2. root `build.gradle.kts` — add to `fastTestModules` **or** `serviceTestModules`. `TestTierCoverageTest` fails the build for a module in neither.
3. `Dockerfile` — `COPY spire-<name>/build.gradle.kts spire-<name>/` in the alphabetical block. `ImageBuildSeesEveryModuleTest` fails otherwise, and without it **every production image build breaks**.
4. `<module>/LICENSE` — Apache-2.0 for libraries and adapters, FSL-1.1-ALv2 for deployables (ADR-021), plus a row in `LICENSING.md`.

**Licence boundary (ADR-021), build-enforced by intent:** no Apache-2.0 module may depend on a service module. `spire-harness`, `spire-harness-codex`, `spire-runtime`, `spire-runtime-docker`, `spire-workspace` are Apache-2.0. `spire-run-worker` and `spire-publisher`
are FSL.

**Framework-free modules:** `spire-harness` and `spire-runtime` carry no framework imports beyond the JDK and their own module — the same rule `PureModulesAreFrameworkFreeTest` enforces for `spire-contract` and `spire-diff`. Add them to that test's list in the task that creates them.

**Java toolchain:** 25, in every new module's `build.gradle.kts`.

**Package roots:** `dev.codespire.harness`, `dev.codespire.harness.codex`, `dev.codespire.runtime`, `dev.codespire.runtime.docker`, `dev.codespire.workspace`, `dev.codespire.runworker`.

**Naming rule (`~/.claude/rules/dto-naming.md`):** transport types are `*Dto` unless they are a read-only projection (`*View`) or a RabbitMQ envelope inner (`*Payload` — not used here).

**Never log a credential.** The machine-account token and the harness credential are injected per run and must not appear in a run event, a log line, an exception message, or a git remote URL.

**Commit style:** imperative, ≤72 chars on the first line, body for anything non-trivial. No mention of AI authorship, tooling, or model names.

**Verification loop:** `./gradlew testFast` for the pure modules, `./gradlew testServices` for the deployables.

---

## File Structure

| Path | Responsibility |
|---|---|
| `spire-harness/…/HarnessAdapter.java` | the SPI: argv, env, one line → one event, exit → outcome, usage |
| `spire-harness/…/RunEvent.java` | normalized event vocabulary (sealed) |
| `spire-harness/…/UsageReport.java` | token usage, with `unknown()` distinct from zero |
| `spire-harness-codex/…/CodexAdapter.java` | `codex exec --json`, NDJSON parsing, exit classification |
| `spire-workspace/…/PublishRepo.java` | the publisher+s bare clone: fetch a bundle, diff, push |
| `spire-workspace/…/PushGate.java` | protected-path refusal, CI floor |
| `spire-runtime/…/RunRuntime.java` | the SPI: create, attach, cancel, finalize, destroy |
| `spire-runtime/…/RuntimeCapabilities.java` | declared flags incl. `nativeSidecar` |
| `spire-runtime/…/RunUnitSpec.java` | the three-container run unit: init, agent, publisher |
| `spire-runtime-docker/…/DockerRunRuntime.java` | one sibling container per run |
| `spire-publisher/…/PublisherMain.java` | the sidecar: watch, fetch, gate, push, report |
| `spire-publisher/…/HandoffWatcher.java` | sees each bundle once, in sequence order |
| `spire-contract/…/command/RunCommand.java` | sealed run-dispatch wire type (`runId()`) |
| `spire-contract/…/event/RunResult.java` | sealed run-result wire type |
| `spire-contract/…/event/RunIds.java` | derive `runId`, parse it back |
| `spire-run-worker/…/RunDispatcher.java` | claim-then-ack consumer |
| `spire-run-worker/…/RunExecutor.java` | workspace → runtime → harness → gate → push |
| `spire-run-worker/…/RunClaimStore.java` | the sole idempotency mechanism |
| `spire-orchestrator/…/factory/RunResource.java` | `POST /api/runs` |
| `spire-orchestrator/…/factory/FactoryRunProjection.java` | the `factory_run` read model |

---

## Task 1: `spire-harness` — the SPI and the run-event vocabulary

**Files:**
- Create: `spire-harness/build.gradle.kts`, `spire-harness/LICENSE`
- Create: `spire-harness/src/main/java/dev/codespire/harness/{HarnessType,HarnessCapabilities,HarnessInvocation,RunEvent,RunEventSummary,TerminalOutcome,FailureCause,UsageReport,TokenBucket,HarnessAdapter}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (`fastTestModules`), `Dockerfile`, `LICENSING.md`
- Modify: `spire-arch/src/test/java/dev/codespire/arch/PureModulesAreFrameworkFreeTest.java`
- Test: `spire-harness/src/test/java/dev/codespire/harness/UsageReportTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HarnessAdapter` with `HarnessType type()`, `HarnessCapabilities capabilities()`, `List<String> command(HarnessInvocation)`, `Map<String,String> environment(HarnessInvocation)`, `Optional<RunEvent> parse(String line)`, `TerminalOutcome classify(int exitCode, RunEventSummary seen)`, `Optional<UsageReport> usage(RunEventSummary seen)`. `UsageReport.unknown()` and `UsageReport.of(Map<TokenBucket,Long>)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.harness;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageReportTest {

    @Test
    void unknownIsNotZero() {
        UsageReport unknown = UsageReport.unknown();
        UsageReport freeCall = UsageReport.of(Map.of(TokenBucket.INPUT, 0L, TokenBucket.OUTPUT, 0L));

        assertTrue(unknown.isUnknown(), "a harness that reported nothing must say so");
        assertFalse(freeCall.isUnknown(), "a call that genuinely used zero tokens is a measurement");
        assertEquals(0L, freeCall.tokens(TokenBucket.INPUT));
    }

    @Test
    void unknownRefusesToAnswerTokenCounts() {
        UsageReport unknown = UsageReport.unknown();
        // An unknown report must not silently answer 0 — that is the shape ADR-023 exists to prevent.
        assertThrows(IllegalStateException.class, () -> unknown.tokens(TokenBucket.INPUT));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-harness:test`
Expected: FAIL — the module does not exist yet, so the build fails at configuration.

- [ ] **Step 3: Create the module and the four-file ritual**

`spire-harness/build.gradle.kts`:

```kotlin
// spire-harness: the agent-execution SPI. Framework-free by rule — the JDK and
// this module only. An adapter turns an invocation into argv plus environment
// and one line of the tool's output into one normalized event; sandboxes,
// credentials, retry and cost belong to the worker (docs/factory/MODULES.md §2).
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
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

Then:
- `settings.gradle.kts`: add `include("spire-harness")` after `include("spire-llm")`.
- root `build.gradle.kts`: add `"spire-harness",` to `fastTestModules`.
- `Dockerfile`: add `COPY spire-harness/build.gradle.kts spire-harness/` in the alphabetical block (between `spire-gateway` and `spire-http`).
- `spire-harness/LICENSE`: copy `spire-diff/LICENSE` (Apache-2.0).
- `LICENSING.md`: add the row.
- `PureModulesAreFrameworkFreeTest`: add `"spire-harness"` to its module list.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.harness;

/** The token dimensions a harness may report. Neutral: each adapter maps its vendor's shape onto these. */
public enum TokenBucket { INPUT, CACHED_INPUT, CACHE_WRITE, OUTPUT, REASONING, TOTAL }
```

```java
package dev.codespire.harness;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a run consumed, or an explicit statement that the harness did not say.
 *
 * <p><b>Unknown is not zero.</b> ADR-023 exists because four separate places turned <i>unknown</i>
 * into <i>zero</i>, and a harness whose usage shape this adapter does not recognise must arrive
 * unpriceable rather than free. {@link #tokens} therefore throws on an unknown report instead of
 * answering a number nobody measured.
 */
public final class UsageReport {

    private static final UsageReport UNKNOWN = new UsageReport(null);

    private final Map<TokenBucket, Long> counts;

    private UsageReport(Map<TokenBucket, Long> counts) {
        this.counts = counts;
    }

    public static UsageReport unknown() {
        return UNKNOWN;
    }

    public static UsageReport of(Map<TokenBucket, Long> counts) {
        Map<TokenBucket, Long> copy = new EnumMap<>(TokenBucket.class);
        counts.forEach((bucket, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException("token count must be >= 0 for " + bucket + ": " + value);
            }
            copy.put(bucket, value);
        });
        return new UsageReport(Map.copyOf(copy));
    }

    public boolean isUnknown() {
        return counts == null;
    }

    public long tokens(TokenBucket bucket) {
        if (counts == null) {
            throw new IllegalStateException("usage is UNKNOWN; ask isUnknown() before reading a count");
        }
        return counts.getOrDefault(bucket, 0L);
    }

    public Optional<Map<TokenBucket, Long>> asMap() {
        return Optional.ofNullable(counts);
    }
}
```

```java
package dev.codespire.harness;

/** The harnesses this distribution can dispatch. A new arm is an adapter plus an entry here. */
public enum HarnessType { CODEX, PI, OPENCODE, CLAUDE_CODE }
```

```java
package dev.codespire.harness;

/** What an adapter can do. The domain reads these; it never branches on {@link HarnessType}. */
public record HarnessCapabilities(boolean streaming, boolean cancel, boolean steer,
                                  boolean resume, boolean structuredOutput) {
}
```

```java
package dev.codespire.harness;

import java.time.Duration;
import java.util.Map;

/**
 * One invocation of a harness. {@code credentials} are injected into the child process's
 * environment and MUST NOT be logged or echoed into a {@link RunEvent}.
 */
public record HarnessInvocation(String runId, String prompt, String workspacePath,
                                String model, Map<String, String> credentials,
                                Duration wallClock) {
}
```

```java
package dev.codespire.harness;

import java.time.Instant;

/**
 * The normalized run-event vocabulary. High-volume and deliberately NOT in spire-contract: most of
 * these never reach the durable domain log (ADR-034), and putting them in the contract module would
 * imply a durability guarantee this tier does not have.
 */
public sealed interface RunEvent {

    Instant at();

    record Thinking(Instant at, String text) implements RunEvent {}

    record ToolUse(Instant at, String tool, String summary) implements RunEvent {}

    record ToolResult(Instant at, String tool, boolean error, String summary) implements RunEvent {}

    record Output(Instant at, String text) implements RunEvent {}

    record StateChange(Instant at, String state, String detail) implements RunEvent {}

    record Usage(Instant at, UsageReport report) implements RunEvent {}
}
```

```java
package dev.codespire.harness;

/**
 * Why a run ended, from a closed set. Recorded as data, because "read the logs" is not a failure
 * cause (FR-F9). PUSH_GATE_REFUSED and SALVAGE_FAILED are added by the worker, not by an adapter.
 */
public enum FailureCause {
    PROVIDER_ERROR, NO_MODEL_RESPONSE, TIMED_OUT, OUT_OF_MEMORY,
    SANDBOX_LOST, SANDBOX_UNREACHABLE, EVICTED,
    DROPPED_COMMIT, SALVAGE_FAILED, PUSH_GATE_REFUSED, BLOCKED_EGRESS,
    HARNESS_EXIT_NONZERO
}
```

```java
package dev.codespire.harness;

import java.util.Optional;

/** How a run ended. A failure always names its cause. */
public record TerminalOutcome(boolean succeeded, Optional<FailureCause> cause, String detail) {

    public static TerminalOutcome success(String detail) {
        return new TerminalOutcome(true, Optional.empty(), detail);
    }

    public static TerminalOutcome failure(FailureCause cause, String detail) {
        return new TerminalOutcome(false, Optional.of(cause), detail);
    }
}
```

```java
package dev.codespire.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What the adapter saw across a whole run, handed back for classification and usage extraction. */
public record RunEventSummary(List<RunEvent> events, boolean sawAnyOutput) {
}
```

```java
package dev.codespire.harness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives one agent harness. Two contract rules that are not obvious:
 *
 * <ul>
 *   <li>{@link #usage} returning empty means UNKNOWN, never zero.</li>
 *   <li>{@link #command} returns argv, never a shell string — a prompt is untrusted text.</li>
 * </ul>
 */
public interface HarnessAdapter {

    HarnessType type();

    HarnessCapabilities capabilities();

    List<String> command(HarnessInvocation invocation);

    Map<String, String> environment(HarnessInvocation invocation);

    /** @return one normalized event, or empty when the line carries nothing the domain models. */
    Optional<RunEvent> parse(String line);

    TerminalOutcome classify(int exitCode, RunEventSummary seen);

    Optional<UsageReport> usage(RunEventSummary seen);
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-harness:test :spire-arch:test`
Expected: PASS. `spire-arch` must also pass — if `TestTierCoverageTest` or `ImageBuildSeesEveryModuleTest` fails, the four-file ritual in Step 3 was incomplete.

- [ ] **Step 6: Commit**

```bash
git add spire-harness settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md \
        spire-arch/src/test/java/dev/codespire/arch/PureModulesAreFrameworkFreeTest.java
git commit -m "Add the harness SPI with unknown-is-not-zero usage"
```

---

## Task 2: `spire-harness-codex` — the first arm

**Files:**
- Create: `spire-harness-codex/build.gradle.kts`, `spire-harness-codex/LICENSE`
- Create: `spire-harness-codex/src/main/java/dev/codespire/harness/codex/CodexAdapter.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`
- Test: `spire-harness-codex/src/test/java/dev/codespire/harness/codex/CodexAdapterTest.java`

**Interfaces:**
- Consumes: `HarnessAdapter`, `HarnessInvocation`, `RunEvent`, `UsageReport`, `TokenBucket`, `TerminalOutcome`, `FailureCause`, `RunEventSummary` from Task 1.
- Produces: `CodexAdapter` implementing `HarnessAdapter`, `type() == HarnessType.CODEX`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.harness.codex;

import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAdapterTest {

    private final CodexAdapter adapter = new CodexAdapter();

    private HarnessInvocation invocation() {
        return new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of("OPENAI_API_KEY", "sk-secret"), Duration.ofMinutes(30));
    }

    @Test
    void buildsTheVerifiedUnattendedInvocation() {
        List<String> argv = adapter.command(invocation(true));

        assertEquals("codex", argv.get(0));
        assertEquals("exec", argv.get(1));
        assertTrue(argv.contains("--json"), "the worker parses NDJSON, not prose");
        assertTrue(argv.contains("--skip-git-repo-check"));

        // Verified against codex-cli 0.152.0: --ask-for-approval DOES NOT EXIST. An earlier draft of
        // this plan asserted it from documentation.
        assertFalse(argv.contains("--ask-for-approval"));

        // danger-full-access means "Codex adds no boundary of its own", not "there is no boundary".
        // Its sandbox is bubblewrap-based and cannot initialize under Docker's default seccomp
        // profile — and it does NOT fail fast when it can't, so any other value is a lie about the
        // security posture. The container is the boundary (ADR-039).
        assertEquals("danger-full-access", argv.get(argv.indexOf("--sandbox") + 1));

        // The prompt is untrusted text from a tracker: a separate argv element, never interpolated
        // into a shell string.
        assertTrue(argv.contains("fix the bug"));
    }

    @Test
    void parsesTheRealEventShape() {
        // Observed from a live run, not from documentation.
        Optional<RunEvent> completed = adapter.parse(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\",\"type\":\"agent_message\","
                        + "\"text\":\"done\"}}");
        Optional<RunEvent> failed = adapter.parse(
                "{\"type\":\"error\",\"message\":\"Reconnecting... waiting for network\"}");
        Optional<RunEvent> noise = adapter.parse("not json at all");

        assertTrue(completed.orElseThrow() instanceof RunEvent.Output);
        assertTrue(failed.orElseThrow() instanceof RunEvent.StateChange);
        assertTrue(noise.isEmpty(), "an unparseable line is skipped, never fatal");
    }

    @Test
    void unrecognisedUsageShapeIsUnknownNotZero() {
        RunEventSummary noUsage = new RunEventSummary(List.of(), true);

        Optional<UsageReport> report = adapter.usage(noUsage);

        assertTrue(report.isEmpty(), "no usage event means UNKNOWN — the ledger must refuse to price it");
    }

    @Test
    void extractsUsageWhenTheHarnessReportsIt() {
        Optional<RunEvent> event = adapter.parse(
                "{\"type\":\"token_count\",\"input_tokens\":120,\"output_tokens\":45,\"cached_input_tokens\":8}");
        RunEventSummary seen = new RunEventSummary(List.of(event.orElseThrow()), true);

        UsageReport report = adapter.usage(seen).orElseThrow();

        assertEquals(120L, report.tokens(TokenBucket.INPUT));
        assertEquals(45L, report.tokens(TokenBucket.OUTPUT));
        assertEquals(8L, report.tokens(TokenBucket.CACHED_INPUT));
    }

    @Test
    void aNegativeVendorCountIsRejectedRatherThanStored() {
        // A buggy OpenAI-compatible proxy once dead-lettered a paid review this way.
        Optional<RunEvent> event = adapter.parse(
                "{\"type\":\"token_count\",\"input_tokens\":-1,\"output_tokens\":10}");

        assertTrue(event.isEmpty(), "a negative count is not a measurement; drop the event, keep the run");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-harness-codex:test`
Expected: FAIL — module and `CodexAdapter` do not exist.

- [ ] **Step 3: Create the module**

`spire-harness-codex/build.gradle.kts` — same shape as Task 1's, plus:

```kotlin
dependencies {
    implementation(project(":spire-harness"))
    // Jackson databind only: this module is an adapter, not a pure module, so it may parse JSON.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Then the four-file ritual: `settings.gradle.kts`, `fastTestModules`, `Dockerfile`, `LICENSE` + `LICENSING.md`.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.harness.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives OpenAI Codex CLI (Apache-2.0) non-interactively.
 *
 * <p>Auth is an API key or a subscription credential the operator registered (ADR-031); this adapter
 * only places what it is given into the child environment. It never logs it.
 */
public final class CodexAdapter implements HarnessAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public HarnessType type() {
        return HarnessType.CODEX;
    }

    @Override
    public HarnessCapabilities capabilities() {
        // Codex exec is one-shot: no session to steer, nothing to resume.
        return new HarnessCapabilities(true, true, false, false, true);
    }

    @Override
    public List<String> command(HarnessInvocation invocation) {
        // Verified against codex-cli 0.152.0, 2026-09-01. Two things the documentation gets wrong:
        // --ask-for-approval does not exist, and the sandbox mode cannot be workspace-write.
        //
        // Codex's Linux sandbox is BUBBLEWRAP (it vendors bwrap), and Docker's default seccomp
        // profile refuses the user namespace bwrap needs. Making it work would require
        // seccomp=unconfined on the container — weakening the OUTER boundary to gain an inner one.
        // And Codex does not fail at startup when its sandbox cannot initialize, so leaving
        // workspace-write set would mean believing in two boundaries while having one.
        //
        // The container is the boundary (ADR-039, RUN-TOPOLOGY §1).
        return List.of(
                "codex", "exec",
                "--json",
                "--sandbox", "danger-full-access",
                "--skip-git-repo-check",
                "--model", invocation.model(),
                "-C", invocation.workspacePath(),
                invocation.prompt());
    }

    @Override
    public Map<String, String> environment(HarnessInvocation invocation) {
        Map<String, String> env = new LinkedHashMap<>(invocation.credentials());
        env.put("CODEX_QUIET_MODE", "1");
        return Map.copyOf(env);
    }

    @Override
    public Optional<RunEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (Exception e) {
            return Optional.empty(); // a line we cannot read is skipped, never fatal
        }
        Instant at = Instant.now();
        String envelope = node.path("type").asText("");

        // The real shape, observed from a live run: an outer envelope whose "type" is item.*,
        // error, or a lifecycle name, with the interesting discriminator on the nested "item".
        if ("error".equals(envelope)) {
            return Optional.of(new RunEvent.StateChange(at, "error", node.path("message").asText("")));
        }
        if (envelope.startsWith("item.")) {
            JsonNode item = node.path("item");
            return switch (item.path("type").asText("")) {
                case "agent_message" -> Optional.of(new RunEvent.Output(at, item.path("text").asText("")));
                case "reasoning" -> Optional.of(new RunEvent.Thinking(at, item.path("text").asText("")));
                case "command_execution" -> Optional.of(
                        new RunEvent.ToolUse(at, "bash", item.path("command").asText("")));
                case "error" -> Optional.of(new RunEvent.StateChange(at, "item_error",
                        item.path("message").asText("")));
                default -> Optional.of(new RunEvent.StateChange(at, envelope, item.path("type").asText("")));
            };
        }
        if ("token_count".equals(envelope) || node.has("input_tokens")) {
            return usageEvent(node, at);
        }
        return Optional.of(new RunEvent.StateChange(at, envelope, ""));
    }

    private Optional<RunEvent> usageEvent(JsonNode node, Instant at) {
        Map<TokenBucket, Long> counts = new EnumMap<>(TokenBucket.class);
        if (!put(counts, TokenBucket.INPUT, node, "input_tokens")
                || !put(counts, TokenBucket.OUTPUT, node, "output_tokens")
                || !put(counts, TokenBucket.CACHED_INPUT, node, "cached_input_tokens")) {
            return Optional.empty(); // a negative count is not a measurement
        }
        return counts.isEmpty() ? Optional.empty() : Optional.of(new RunEvent.Usage(at, UsageReport.of(counts)));
    }

    private boolean put(Map<TokenBucket, Long> into, TokenBucket bucket, JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            return true;
        }
        long value = node.path(field).asLong();
        if (value < 0) {
            return false;
        }
        into.put(bucket, value);
        return true;
    }

    @Override
    public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
        if (exitCode == 0) {
            return TerminalOutcome.success("codex exec completed");
        }
        if (!seen.sawAnyOutput()) {
            return TerminalOutcome.failure(FailureCause.NO_MODEL_RESPONSE, "exit " + exitCode + ", no output");
        }
        return TerminalOutcome.failure(FailureCause.HARNESS_EXIT_NONZERO, "exit " + exitCode);
    }

    @Override
    public Optional<UsageReport> usage(RunEventSummary seen) {
        List<RunEvent.Usage> reports = new ArrayList<>();
        for (RunEvent event : seen.events()) {
            if (event instanceof RunEvent.Usage usage) {
                reports.add(usage);
            }
        }
        // Empty means UNKNOWN. Never a zeroed report.
        return reports.isEmpty() ? Optional.empty() : Optional.of(reports.getLast().report());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-harness-codex:test :spire-arch:test`
Expected: PASS.

- [ ] **Step 6: Pin the event shape against a live run, and settle the usage question**

The envelope shapes in Step 4 come from a real run (`item.completed`, `error`), but the nested
`item.type` values are still partly inferred, and one question is open and matters.

Run, against the agent image from Task 6b and a real credential:

```bash
codex exec --json --sandbox danger-full-access --skip-git-repo-check -C /w   "Read README.md, then run: ls -la. Then say done." | tee /tmp/stream.ndjson
jq -r ".type, (.item.type // empty)" /tmp/stream.ndjson | sort | uniq -c
grep -c token /tmp/stream.ndjson
```

Record two outcomes:

1. **The real set of `type` / `item.type` values.** Fix `parse` and the test fixtures to what the
   run actually emitted. The test pins the real wire shape, never a guessed one.
2. **Whether token usage appears DURING the run or only in the final summary.** Not a blocker —
   Codex runs on a subscription, so a killed run loses no money, only statistics (RUN-TOPOLOGY §10).
   Record the answer there anyway: it decides how much data survives a killed run, and it becomes
   accounting again the day an arm runs on an API key. Usage is written beside each bundle in
   `/handoff`, so whatever the harness reports per turn is captured at the last checkpoint; a harness
   that reports only at the end yields **UNKNOWN**, never zero.

- [ ] **Step 7: Commit**

```bash
git add spire-harness-codex settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the Codex harness adapter with NDJSON event parsing"
```

---

## Task 3: `spire-workspace` — the publisher's git library

**Where it runs:** inside the **publisher image**, not the worker (ADR-039). The worker holds no
filesystem and runs no git at all. The agent's own clone is made by an init container and its bundles
are written by shell in the agent image; this library is used only by the publisher, on its **own**
pristine clone.

**Files:**
- Create: `spire-workspace/build.gradle.kts`, `spire-workspace/LICENSE`
- Create: `spire-workspace/src/main/java/dev/codespire/workspace/{PublishRepo,ChangeSet,ChangedPath,ChangeKind,GitCredential,BundleTooLargeException}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (`fastTestModules`), `Dockerfile`, `LICENSING.md`
- Test: `spire-workspace/src/test/java/dev/codespire/workspace/PublishRepoTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `PublishRepo.cloneBranch(String remoteUri, String branch, Path dir, GitCredential cred)`;
  `PublishRepo.fetchBundle(Path bundle, long maxBytes) -> String fetchedSha`;
  `PublishRepo.changesSince(String baseCommit, String sha) -> ChangeSet`;
  `PublishRepo.pushRef(String sha, String branch, GitCredential cred) -> String pushedRef`.
  `ChangedPath(String path, ChangeKind kind)`; `ChangeKind` = `ADDED|MODIFIED|DELETED|RENAMED_FROM|RENAMED_TO`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishRepoTest {

    private void git(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", argv));
    }

    private String rev(Path repo, String ref) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", ref).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor());
        return out;
    }

    /** A bare origin plus a seed clone, standing in for the forge. No network. */
    private Path origin(Path dir) throws Exception {
        Path bare = dir.resolve("origin.git");
        Files.createDirectories(bare);
        git(bare, "git", "init", "--bare", "--initial-branch=main");

        Path seed = dir.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "git", "clone", bare.toUri().toString(), ".");
        git(seed, "git", "config", "user.email", "t@t");
        git(seed, "git", "config", "user.name", "t");
        Files.writeString(seed.resolve("README.md"), "hello\n");
        Files.createDirectories(seed.resolve(".github/workflows"));
        Files.writeString(seed.resolve(".github/workflows/ci.yml"), "on: push\n");
        git(seed, "git", "add", "-A");
        git(seed, "git", "commit", "-m", "base");
        git(seed, "git", "push", "origin", "main");
        return bare;
    }

    /** Stands in for the AGENT container: its own clone, its own commits, its own bundle. */
    private Path agentBundle(Path dir, Path bare, String base) throws Exception {
        Path ws = dir.resolve("agent-ws");
        git(dir, "git", "clone", bare.toUri().toString(), ws.toString());
        git(ws, "git", "config", "user.email", "bot@spire");
        git(ws, "git", "config", "user.name", "spire-bot");
        git(ws, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(ws.resolve("NEW.md"), "new\n");
        Files.delete(ws.resolve(".github/workflows/ci.yml"));
        Files.move(ws.resolve("README.md"), ws.resolve("DOCS.md"));
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "agent work");

        Path bundle = dir.resolve("delta.bundle");
        git(ws, "git", "bundle", "create", bundle.toString(), base + "..HEAD");
        return bundle;
    }

    @Test
    void fetchesABundleAndSeesEveryChangedPath(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null);
        String sha = repo.fetchBundle(bundle, 10_000_000L);

        Set<String> paths = repo.changesSince(base, sha).paths().stream()
                .map(ChangedPath::path).collect(Collectors.toSet());

        assertTrue(paths.contains("NEW.md"));
        assertTrue(paths.contains(".github/workflows/ci.yml"), "a deleted workflow must still be seen");
        assertTrue(paths.contains("README.md"), "the rename's source side must be seen");
        assertTrue(paths.contains("DOCS.md"), "the rename's target side must be seen");
    }

    @Test
    void pushesTheFetchedCommitToTheBranch(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null);
        String sha = repo.fetchBundle(bundle, 10_000_000L);
        String pushed = repo.pushRef(sha, "spire/run_1", null);

        assertEquals("refs/heads/spire/run_1", pushed);
        assertEquals(sha, rev(bare, "refs/heads/spire/run_1"), "the commit must be on the real remote");
    }

    @Test
    void refusesABundleOverTheSizeCap(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null);

        // An agent can write an object bomb; an unbounded read is a denial of service.
        assertThrows(BundleTooLargeException.class, () -> repo.fetchBundle(bundle, 16L));
    }

    @Test
    void neverCreatesAWorkingTree(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        Path publish = dir.resolve("publish");
        PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main", publish, null);
        repo.fetchBundle(bundle, 10_000_000L);

        // Agent-authored content must never become a file on the publisher's disk (ADR-039).
        assertTrue(Files.notExists(publish.resolve("NEW.md")));
        assertTrue(Files.notExists(publish.resolve("DOCS.md")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-workspace:test`
Expected: FAIL — module and types do not exist.

- [ ] **Step 3: Create the module**

`spire-workspace/build.gradle.kts` — Task 1's shape, plus:

```kotlin
dependencies {
    // JGit rather than shelling out: the publisher image then needs no git binary, and
    // clone/fetch-bundle/diff/push are testable in-process against a local origin with no network.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

If that JGit version fails to resolve, run
`./gradlew :spire-workspace:dependencies --refresh-dependencies` and pin the newest published `7.x`.
Do not leave it unpinned.

Then the four-file ritual from Global Constraints.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.workspace;

/** Credentials for a git remote. Never rendered into a URL — a URL reaches the log stream. */
public record GitCredential(String username, String secret) {

    @Override
    public String toString() {
        return "GitCredential[username=" + username + ", secret=***]";
    }
}
```

```java
package dev.codespire.workspace;

public enum ChangeKind { ADDED, MODIFIED, DELETED, RENAMED_FROM, RENAMED_TO }
```

```java
package dev.codespire.workspace;

public record ChangedPath(String path, ChangeKind kind) {
}
```

```java
package dev.codespire.workspace;

import java.util.List;

public record ChangeSet(List<ChangedPath> paths) {

    public boolean isEmpty() {
        return paths.isEmpty();
    }
}
```

```java
package dev.codespire.workspace;

/** An agent can write an object bomb. An unbounded read is a denial of service on the publisher. */
public class BundleTooLargeException extends RuntimeException {

    public BundleTooLargeException(long actual, long max) {
        super("bundle is " + actual + " bytes, cap is " + max);
    }
}
```

```java
package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The publisher's own pristine clone of the repository.
 *
 * <p><b>It never reads the agent's workspace and never checks out a working tree.</b> Agent work
 * arrives only as a git bundle — objects and refs, carrying no config and no hooks — so nothing the
 * agent authored can execute here, and nothing it authored becomes a file on this disk. That is what
 * makes it safe for this process to hold a write credential (ADR-039).
 */
public final class PublishRepo implements AutoCloseable {

    private static final String BUNDLE_REFS = "refs/bundle/";

    private final Git git;
    private final Path directory;

    private PublishRepo(Git git, Path directory) {
        this.git = git;
        this.directory = directory;
    }

    /** A BARE clone: no working tree exists, so none can be written into. */
    public static PublishRepo cloneBranch(String remoteUri, String branch, Path dir,
                                          GitCredential credential) throws Exception {
        Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(dir.toFile())
                .setBare(true)
                .setBranch(branch)
                .setCredentialsProvider(provider(credential))
                .call();
        return new PublishRepo(git, dir);
    }

    /** @return the sha the bundle's head resolved to. */
    public String fetchBundle(Path bundle, long maxBytes) throws Exception {
        long size = Files.size(bundle);
        if (size > maxBytes) {
            throw new BundleTooLargeException(size, maxBytes);
        }
        git.fetch()
                .setRemote(bundle.toAbsolutePath().toString())
                .setRefSpecs(new RefSpec("+refs/heads/*:" + BUNDLE_REFS + "*"))
                .call();
        List<Ref> fetched = git.getRepository().getRefDatabase().getRefsByPrefix(BUNDLE_REFS);
        if (fetched.isEmpty()) {
            throw new IOException("bundle contained no branch");
        }
        return fetched.getFirst().getObjectId().name();
    }

    /** Every path changed between {@code baseCommit} and {@code sha}, renames on both sides. */
    public ChangeSet changesSince(String baseCommit, String sha) throws IOException {
        Repository repo = git.getRepository();
        try (var reader = repo.newObjectReader(); RevWalk walk = new RevWalk(repo)) {
            CanonicalTreeParser base = new CanonicalTreeParser();
            base.reset(reader, walk.parseCommit(ObjectId.fromString(baseCommit)).getTree());
            CanonicalTreeParser head = new CanonicalTreeParser();
            head.reset(reader, walk.parseCommit(ObjectId.fromString(sha)).getTree());

            List<ChangedPath> paths = new ArrayList<>();
            for (DiffEntry entry : git.diff().setOldTree(base).setNewTree(head).call()) {
                switch (entry.getChangeType()) {
                    case ADD -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.ADDED));
                    case MODIFY -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.MODIFIED));
                    case DELETE -> paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.DELETED));
                    case RENAME, COPY -> {
                        // BOTH sides. A rename INTO a protected path is the obvious evasion.
                        paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.RENAMED_FROM));
                        paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.RENAMED_TO));
                    }
                }
            }
            return new ChangeSet(List.copyOf(paths));
        } catch (Exception e) {
            throw new IOException("could not diff " + sha + " against " + baseCommit, e);
        }
    }

    /** Call ONLY after the push gate has passed. @return the pushed ref. */
    public String pushRef(String sha, String branch, GitCredential credential) throws Exception {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec(sha + ":refs/heads/" + branch))
                .setCredentialsProvider(provider(credential))
                .call();
        return "refs/heads/" + branch;
    }

    public Path path() {
        return directory;
    }

    private static UsernamePasswordCredentialsProvider provider(GitCredential credential) {
        return credential == null
                ? null
                : new UsernamePasswordCredentialsProvider(credential.username(), credential.secret());
    }

    @Override
    public void close() {
        git.close();
    }
}
```

> `fetchBundle`'s refspec is written from JGit's documented behaviour, not from a captured run. Run
> the test first; if `refs/bundle/*` is not populated as written, print what
> `git.getRepository().getRefDatabase().getRefs()` actually holds after the fetch and fix the code to
> that. Do not adjust the test to match a wrong implementation.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-workspace:test :spire-arch:test`
Expected: PASS. `git` must be on the test machine's PATH — the tests build their origin and their
bundle with it, standing in for the init container and the agent. Production needs no git binary.

- [ ] **Step 6: Commit**

```bash
git add spire-workspace settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the publisher's git library: clone, fetch a bundle, diff, push"
```

---

## Task 4: The push gate

**Files:**
- Create: `spire-workspace/src/main/java/dev/codespire/workspace/{PushGate,PushDecision,ProtectedPaths}.java`
- Test: `spire-workspace/src/test/java/dev/codespire/workspace/PushGateTest.java`

**Interfaces:**
- Consumes: `ChangeSet`, `ChangedPath` from Task 3.
- Produces: `PushGate.decide(ChangeSet, List<String> profileGlobs) -> PushDecision`; `PushDecision.allowed() -> boolean`, `PushDecision.blocked() -> List<String>`; `ProtectedPaths.CI_FLOOR` as `List<String>`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushGateTest {

    private ChangeSet changed(String... paths) {
        return new ChangeSet(List.of(paths).stream()
                .map(p -> new ChangedPath(p, ChangeKind.MODIFIED)).toList());
    }

    @Test
    void allowsAnOrdinaryChange() {
        PushDecision decision = PushGate.decide(changed("src/main/java/Foo.java"), List.of());

        assertTrue(decision.allowed());
        assertEquals(List.of(), decision.blocked());
    }

    @Test
    void refusesAWorkflowEditOnEveryProfile() {
        PushDecision decision = PushGate.decide(changed(".github/workflows/ci.yml"), List.of());

        assertFalse(decision.allowed(), "CI configuration is a floor no profile may lower");
        assertEquals(List.of(".github/workflows/ci.yml"), decision.blocked());
    }

    @Test
    void refusesADeletedWorkflowToo() {
        ChangeSet deleted = new ChangeSet(List.of(
                new ChangedPath(".gitlab-ci.yml", ChangeKind.DELETED)));

        assertFalse(PushGate.decide(deleted, List.of()).allowed(),
                "deleting CI changes what CI does exactly as much as editing it");
    }

    @Test
    void refusesARenameIntoAProtectedPath() {
        ChangeSet renamed = new ChangeSet(List.of(
                new ChangedPath("scripts/x.yml", ChangeKind.RENAMED_FROM),
                new ChangedPath(".github/workflows/x.yml", ChangeKind.RENAMED_TO)));

        assertFalse(PushGate.decide(renamed, List.of()).allowed());
    }

    @Test
    void theCiFloorMatchesCaseInsensitively() {
        // Different path to git, same file to a case-insensitive filesystem, and the forge runs it.
        assertFalse(PushGate.decide(changed(".GitHub/Workflows/ci.yml"), List.of()).allowed());
    }

    @Test
    void aProfileMayProtectMore() {
        PushDecision decision = PushGate.decide(changed("deploy/values.yaml"), List.of("deploy/**"));

        assertFalse(decision.allowed());
        assertEquals(List.of("deploy/values.yaml"), decision.blocked());
    }

    @Test
    void aProfileCannotUnprotectTheFloor() {
        // An empty profile list, a permissive one, and a hostile one all behave identically.
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of("**")).allowed());
        assertFalse(PushGate.decide(changed("Jenkinsfile"), List.of()).allowed());
    }

    @Test
    void namesEveryBlockedPathNotJustTheFirst() {
        PushDecision decision = PushGate.decide(
                changed(".github/workflows/a.yml", "src/Ok.java", "Jenkinsfile"), List.of());

        assertEquals(List.of(".github/workflows/a.yml", "Jenkinsfile"), decision.blocked());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-workspace:test --tests '*PushGateTest*'`
Expected: FAIL — `PushGate` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package dev.codespire.workspace;

import java.util.List;

/**
 * Paths the factory may never push a change to.
 *
 * <p><b>A floor, not a setting (ADR-037).</b> A pushed branch executes its own CI workflow files on
 * an unsandboxed runner holding repository secrets, and the prompt that produced the branch contains
 * untrusted tracker text. The input that would authorise the change is the input under suspicion, so
 * no profile may unprotect these — the same shape as the never-suppressed SECURITY floor in ADR-027.
 */
public final class ProtectedPaths {

    public static final List<String> CI_FLOOR = List.of(
            ".github/workflows/**",
            ".github/actions/**",
            ".gitlab-ci.yml",
            ".gitlab/**",
            "bitbucket-pipelines.yml",
            "Jenkinsfile",
            ".circleci/**",
            "azure-pipelines.yml");

    private ProtectedPaths() {
    }
}
```

```java
package dev.codespire.workspace;

import java.util.List;

/** Why a push was allowed or refused. A refusal names every blocked path, not just the first. */
public record PushDecision(boolean allowed, List<String> blocked) {

    public static PushDecision allow() {
        return new PushDecision(true, List.of());
    }

    public static PushDecision refuse(List<String> blocked) {
        return new PushDecision(false, List.copyOf(blocked));
    }
}
```

```java
package dev.codespire.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses a push whose branch touches a protected path (FR-F28).
 *
 * <p>Matching uses the JDK's {@code glob:} {@link PathMatcher}, so the product gains no glob dialect
 * of its own. (An early draft named {@code PathGlobs} for this; that class does the opposite job —
 * it maps a path TO the group glob a learned preference is about, and cannot match one against a
 * glob.)
 */
public final class PushGate {

    private PushGate() {
    }

    public static PushDecision decide(ChangeSet changes, List<String> profileGlobs) {
        List<PathMatcher> floor = compile(ProtectedPaths.CI_FLOOR);
        List<PathMatcher> floorLowercase = compile(
                ProtectedPaths.CI_FLOOR.stream().map(g -> g.toLowerCase(Locale.ROOT)).toList());
        List<PathMatcher> profile = compile(profileGlobs);

        List<String> blocked = new ArrayList<>();
        for (ChangedPath changed : changes.paths()) {
            String path = changed.path();
            if (path == null || path.isBlank() || "/dev/null".equals(path)) {
                continue;
            }
            Path asPath = Path.of(path);
            Path lowercase = Path.of(path.toLowerCase(Locale.ROOT));

            boolean hitsFloor = matchesAny(floor, asPath) || matchesAny(floorLowercase, lowercase);
            boolean hitsProfile = matchesAny(profile, asPath);

            if ((hitsFloor || hitsProfile) && !blocked.contains(path)) {
                blocked.add(path);
            }
        }
        return blocked.isEmpty() ? PushDecision.allow() : PushDecision.refuse(blocked);
    }

    private static List<PathMatcher> compile(List<String> globs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : globs) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
        }
        return matchers;
    }

    private static boolean matchesAny(List<PathMatcher> matchers, Path path) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :spire-workspace:test`
Expected: PASS — all eight `PushGateTest` cases plus Task 3's.

- [ ] **Step 5: Mutation-verify the floor**

Temporarily delete `".github/workflows/**"` from `ProtectedPaths.CI_FLOOR`, run the tests, and confirm **`refusesAWorkflowEditOnEveryProfile` fails and nothing else does**. Then restore it. A guard that passes against its own mutation is not a guard — this project has shipped one.

- [ ] **Step 6: Commit**

```bash
git add spire-workspace/src
git commit -m "Refuse a push that touches CI configuration, on every profile"
```

---

## Task 5: `spire-runtime` — the placement SPI

**Files:**
- Create: `spire-runtime/build.gradle.kts`, `spire-runtime/LICENSE`
- Create: `spire-runtime/src/main/java/dev/codespire/runtime/{RuntimeType,RuntimeCapabilities,ContainerSpec,RunUnitSpec,RunHandle,LogChannel,Finalization,RunRuntime}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`, `PureModulesAreFrameworkFreeTest`
- Test: `spire-runtime/src/test/java/dev/codespire/runtime/FinalizationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RunRuntime` with `RuntimeType type()`, `RuntimeCapabilities capabilities()`,
  `RunHandle create(RunUnitSpec)`, `void attach(RunHandle, LogChannel, Consumer<String>)`,
  `void cancel(RunHandle)`, `Finalization finalize(RunHandle)`, `void destroy(RunHandle)`,
  `List<RunHandle> discoverOrphans()`. `LogChannel` = `AGENT | PUBLISHER`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizationTest {

    @Test
    void aFailedSalvageIsNotASuccessfulRun() {
        Finalization failed = Finalization.salvageFailed("publisher never reported an outcome");

        assertFalse(failed.salvaged(),
                "destroy must not proceed on a failed salvage — that is the loss finalize prevents");
        assertTrue(failed.detail().contains("outcome"));
    }

    @Test
    void capabilitiesDeclareNativeSidecarSupportRatherThanAssumingIt() {
        // Kubernetes >= 1.29 terminates a sidecar when the main container exits. Below that, the
        // publisher needs an explicit sentinel file to know the agent finished (RUN-TOPOLOGY §3).
        RuntimeCapabilities modern = new RuntimeCapabilities(true, true, false, true, true, true);
        RuntimeCapabilities old = new RuntimeCapabilities(true, true, false, true, true, false);

        assertTrue(modern.nativeSidecar());
        assertFalse(old.nativeSidecar());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-runtime:test`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Create the module (four-file ritual, `fastTestModules`)**

`spire-runtime/build.gradle.kts` — identical to Task 1's, no dependencies beyond JUnit.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.runtime;

public enum RuntimeType { DOCKER, KUBERNETES }
```

```java
package dev.codespire.runtime;

/** Which container's output is being read. A run has two log streams that mean different things. */
public enum LogChannel { AGENT, PUBLISHER }
```

```java
package dev.codespire.runtime;

/**
 * What a runtime can do. The domain reads these; it never branches on {@link RuntimeType}.
 *
 * <p>{@code nativeSidecar} is Kubernetes >= 1.29's sidecar termination. Where it is absent the
 * publisher must learn the agent finished from a sentinel file instead.
 */
public record RuntimeCapabilities(boolean networkPolicy, boolean resourceLimits, boolean steering,
                                  boolean archival, boolean garbageCollection, boolean nativeSidecar) {
}
```

```java
package dev.codespire.runtime;

import java.util.List;
import java.util.Map;

/**
 * One container in a run unit.
 *
 * <p>{@code environment} carries injected credentials and MUST NOT be logged; the docker arm
 * additionally keeps them out of container labels, which {@code docker inspect} prints.
 *
 * <p>{@code mounts} maps a shared volume name to its mount path. A path suffixed {@code :ro} is
 * read-only — which is how {@code /handoff} reaches the publisher, and why the publisher writes
 * nothing to any shared volume (ADR-039).
 */
public record ContainerSpec(String image, List<String> argv, Map<String, String> environment,
                            Map<String, String> mounts) {
}
```

```java
package dev.codespire.runtime;

import java.time.Duration;

/**
 * A run is not one container. It is an init clone, the agent, and the publisher sidecar, sharing
 * ephemeral volumes and nothing outside the unit (ADR-039, RUN-TOPOLOGY §3).
 *
 * <p>The parts run in this order: {@code init} to completion, then {@code agent} and
 * {@code publisher} concurrently. The unit ends when the agent exits and the publisher has drained.
 */
public record RunUnitSpec(String runId,
                          ContainerSpec init, ContainerSpec agent, ContainerSpec publisher,
                          long memoryBytes, long nanoCpus, Duration wallClock) {
}
```

```java
package dev.codespire.runtime;

/** An opaque handle to a live run unit. {@code providerRunId} is a pod name or a docker unit id. */
public record RunHandle(String runId, String providerRunId) {
}
```

```java
package dev.codespire.runtime;

/**
 * The result of finalizing a run — salvage, BEFORE teardown.
 *
 * <p>{@code destroy} runs only when {@link #salvaged()} is true. A failed salvage preserves the unit,
 * because "the agent did the work and the container died with it" was the second most common failure
 * in the prior art this design learned from.
 */
public record Finalization(int exitCode, boolean salvaged, String detail) {

    public static Finalization salvaged(int exitCode, String detail) {
        return new Finalization(exitCode, true, detail);
    }

    public static Finalization salvageFailed(String detail) {
        return new Finalization(-1, false, detail);
    }
}
```

```java
package dev.codespire.runtime;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where a run unit runs and how its life is controlled.
 *
 * <p>{@link #finalize} and {@link #destroy} are separate on purpose. Merging them is how completed
 * work gets thrown away.
 */
public interface RunRuntime {

    RuntimeType type();

    RuntimeCapabilities capabilities();

    RunHandle create(RunUnitSpec spec);

    /** Streams one container's stdout line by line until it exits. */
    void attach(RunHandle handle, LogChannel channel, Consumer<String> lines);

    void cancel(RunHandle handle);

    Finalization finalize(RunHandle handle);

    void destroy(RunHandle handle);

    /** Units this runtime holds that no live lease claims. See ARCHITECTURE §7. */
    List<RunHandle> discoverOrphans();
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-runtime:test :spire-arch:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spire-runtime settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md \
        spire-arch/src/test/java/dev/codespire/arch/PureModulesAreFrameworkFreeTest.java
git commit -m "Add the run-placement SPI with salvage separate from teardown"
```

---

## Task 6: `spire-runtime-docker` — the three-container run unit

**Files:**
- Create: `spire-runtime-docker/build.gradle.kts`, `spire-runtime-docker/LICENSE`
- Create: `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`
- Test: `spire-runtime-docker/src/test/java/dev/codespire/runtime/docker/DockerRunRuntimeIT.java`

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: `DockerRunRuntime` implementing `RunRuntime`.

**Docker has no pods**, so this adapter builds the unit by hand: two named volumes, the init container
run to completion, then the agent and publisher started concurrently on the same volumes. Everything
is labelled with the run id so the orphan watchdog can find what a restart forgot.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.runtime.docker;

import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration test: needs a working Docker daemon. In testFast — it is fast, not pure. */
class DockerRunRuntimeIT {

    private final DockerRunRuntime runtime = new DockerRunRuntime();

    private RunUnitSpec unit(String id, String agentScript, String publisherScript) {
        // init seeds /workspace; agent writes into it and drops a file in /handoff;
        // publisher reads /handoff READ-ONLY and must not be able to see /workspace at all.
        return new RunUnitSpec(id,
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", "echo seeded > /workspace/seed.txt"),
                        Map.of(), Map.of("ws", "/workspace", "ho", "/handoff")),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", agentScript),
                        Map.of(), Map.of("ws", "/workspace", "ho", "/handoff")),
                new ContainerSpec("alpine:3.20", List.of("sh", "-c", publisherScript),
                        Map.of(), Map.of("ho", "/handoff:ro")),
                256L * 1024 * 1024, 1_000_000_000L, Duration.ofMinutes(2));
    }

    @Test
    void runsInitThenAgentAndPublisherOnSharedVolumes() {
        RunHandle handle = runtime.create(unit("run_unit1",
                "cat /workspace/seed.txt; echo handed-over > /handoff/delta; echo agent-done",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && { cat /handoff/delta; "
                        + "echo publisher-done; exit 0; }; sleep 1; done; exit 1"));

        List<String> agent = new ArrayList<>();
        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.AGENT, agent::add);
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        Finalization finalization = runtime.finalize(handle);
        runtime.destroy(handle);

        assertTrue(agent.contains("seeded"), "the init container's output must be in the workspace");
        assertTrue(agent.contains("agent-done"));
        assertTrue(publisher.contains("handed-over"), "the handoff volume must be shared");
        assertTrue(publisher.contains("publisher-done"));
        assertEquals(0, finalization.exitCode());
        assertTrue(finalization.salvaged());
    }

    @Test
    void thePublisherCannotSeeTheAgentsWorkspace() {
        RunHandle handle = runtime.create(unit("run_unit2",
                "echo secret > /workspace/private.txt; echo x > /handoff/delta",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && break; sleep 1; done; "
                        + "if [ -e /workspace ]; then echo LEAKED; else echo isolated; fi"));

        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        runtime.finalize(handle);
        runtime.destroy(handle);

        // ADR-039: the publisher must never reach agent-controlled git config or hooks.
        assertTrue(publisher.contains("isolated"));
        assertTrue(!publisher.contains("LEAKED"));
    }

    @Test
    void handoffIsReadOnlyToThePublisher() {
        RunHandle handle = runtime.create(unit("run_unit3",
                "echo x > /handoff/delta; echo agent-done",
                "for i in $(seq 1 30); do [ -f /handoff/delta ] && break; sleep 1; done; "
                        + "if echo y > /handoff/evil 2>/dev/null; then echo WRITABLE; else echo readonly; fi"));

        List<String> publisher = new ArrayList<>();
        runtime.attach(handle, LogChannel.PUBLISHER, publisher::add);
        runtime.finalize(handle);
        runtime.destroy(handle);

        assertTrue(publisher.contains("readonly"));
    }

    @Test
    void reportsANonZeroAgentExitRatherThanThrowing() {
        RunHandle handle = runtime.create(unit("run_unit4",
                "exit 3", "echo publisher-idle"));

        runtime.attach(handle, LogChannel.AGENT, line -> { });
        Finalization finalization = runtime.finalize(handle);
        runtime.destroy(handle);

        assertEquals(3, finalization.exitCode());
    }

    @Test
    void discoversItsOwnUnitsAsOrphansUntilDestroyed() {
        RunHandle handle = runtime.create(unit("run_unit5", "sleep 5", "sleep 5"));

        assertTrue(runtime.discoverOrphans().stream().anyMatch(h -> h.runId().equals("run_unit5")));

        runtime.finalize(handle);
        runtime.destroy(handle);

        assertTrue(runtime.discoverOrphans().stream().noneMatch(h -> h.runId().equals("run_unit5")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-runtime-docker:test`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Create the module (four-file ritual, `fastTestModules`)**

```kotlin
dependencies {
    implementation(project(":spire-runtime"))
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

If `3.4.1` fails to resolve, pin the newest published `3.4.x`.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A run unit as three Docker containers over two named volumes.
 *
 * <p>Docker has no pods, so the ordering Kubernetes gives for free is done here: the init container
 * runs to completion, then the agent and publisher start concurrently on the same volumes.
 *
 * <p><b>Socket access is root-equivalent on the host.</b> Stated in SECURITY.md rather than mitigated
 * away; the Kubernetes arm removes it.
 *
 * <p><b>Codex's own sandbox is NOT used</b> (ADR-039): it is bubblewrap-based and cannot initialize
 * under Docker's default seccomp profile, and it does not fail fast when it cannot. The container is
 * the boundary, so the default seccomp profile is KEPT and never relaxed here.
 */
public final class DockerRunRuntime implements RunRuntime {

    static final String RUN_ID_LABEL = "dev.codespire.runId";
    static final String ROLE_LABEL = "dev.codespire.role";

    private final DockerClient client;
    private final Map<String, Unit> units = new HashMap<>();

    private record Unit(String initId, String agentId, String publisherId, List<String> volumes) {
    }

    public DockerRunRuntime() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.client = DockerClientImpl.getInstance(config, http);
    }

    DockerClient client() {
        return client;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.DOCKER;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        // No native sidecar semantics in Docker: this adapter sequences the parts itself.
        return new RuntimeCapabilities(false, true, false, true, true, false);
    }

    @Override
    public RunHandle create(RunUnitSpec spec) {
        List<String> volumeNames = new ArrayList<>();
        for (String name : allVolumeNames(spec)) {
            String full = "spire-" + spec.runId() + "-" + name;
            client.createVolumeCmd().withName(full).withLabels(Map.of(RUN_ID_LABEL, spec.runId())).exec();
            volumeNames.add(full);
        }

        String initId = createContainer(spec, spec.init(), "init");
        client.startContainerCmd(initId).exec();
        int initExit = client.waitContainerCmd(initId)
                .exec(new WaitContainerResultCallback()).awaitStatusCode();
        if (initExit != 0) {
            throw new IllegalStateException("init container failed with exit " + initExit);
        }

        String agentId = createContainer(spec, spec.agent(), "agent");
        String publisherId = createContainer(spec, spec.publisher(), "publisher");
        client.startContainerCmd(publisherId).exec();   // sidecar first, so it misses nothing
        client.startContainerCmd(agentId).exec();

        units.put(spec.runId(), new Unit(initId, agentId, publisherId, volumeNames));
        return new RunHandle(spec.runId(), agentId);
    }

    private List<String> allVolumeNames(RunUnitSpec spec) {
        List<String> names = new ArrayList<>();
        for (ContainerSpec c : List.of(spec.init(), spec.agent(), spec.publisher())) {
            for (String v : c.mounts().keySet()) {
                if (!names.contains(v)) {
                    names.add(v);
                }
            }
        }
        return names;
    }

    private String createContainer(RunUnitSpec spec, ContainerSpec container, String role) {
        List<Bind> binds = new ArrayList<>();
        container.mounts().forEach((volume, target) -> {
            boolean readOnly = target.endsWith(":ro");
            String path = readOnly ? target.substring(0, target.length() - 3) : target;
            Bind bind = new Bind("spire-" + spec.runId() + "-" + volume, new Volume(path), readOnly);
            binds.add(bind);
        });

        List<String> env = new ArrayList<>();
        container.environment().forEach((k, v) -> env.add(k + "=" + v));

        HostConfig host = HostConfig.newHostConfig()
                .withBinds(binds)
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withAutoRemove(false);   // finalize must be able to read the exit code

        return client.createContainerCmd(container.image())
                .withCmd(container.argv())
                .withEnv(env)             // credentials live HERE, never in a label
                .withLabels(Map.of(RUN_ID_LABEL, spec.runId(), ROLE_LABEL, role))
                .withHostConfig(host)
                .exec()
                .getId();
    }

    @Override
    public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        Unit unit = units.get(handle.runId());
        String containerId = channel == LogChannel.AGENT ? unit.agentId() : unit.publisherId();
        try {
            client.logContainerCmd(containerId)
                    .withStdOut(true).withStdErr(true).withFollowStream(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            String chunk = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            for (String line : chunk.split("\\R")) {
                                if (!line.isEmpty()) {
                                    lines.accept(line);
                                }
                            }
                        }
                    })
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void cancel(RunHandle handle) {
        Unit unit = units.get(handle.runId());
        for (String id : List.of(unit.agentId(), unit.publisherId())) {
            try {
                client.killContainerCmd(id).exec();
            } catch (Exception e) {
                // already stopped
            }
        }
    }

    @Override
    public Finalization finalize(RunHandle handle) {
        Unit unit = units.get(handle.runId());
        try {
            int agentExit = client.waitContainerCmd(unit.agentId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            // Give the publisher its drain window, then stop it. It has no work left once the agent
            // is gone and the last bundle has been read.
            try {
                client.stopContainerCmd(unit.publisherId()).withTimeout(30).exec();
            } catch (Exception e) {
                // already exited
            }
            return Finalization.salvaged(agentExit, "agent exited " + agentExit);
        } catch (Exception e) {
            return Finalization.salvageFailed("could not read the agent's exit code: " + e.getMessage());
        }
    }

    @Override
    public void destroy(RunHandle handle) {
        Unit unit = units.remove(handle.runId());
        if (unit == null) {
            return;
        }
        for (String id : List.of(unit.initId(), unit.agentId(), unit.publisherId())) {
            try {
                client.removeContainerCmd(id).withForce(true).exec();
            } catch (Exception e) {
                // already gone
            }
        }
        for (String volume : unit.volumes()) {
            try {
                client.removeVolumeCmd(volume).exec();
            } catch (Exception e) {
                // already gone
            }
        }
    }

    @Override
    public List<RunHandle> discoverOrphans() {
        List<RunHandle> handles = new ArrayList<>();
        client.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(ROLE_LABEL, "agent"))
                .exec()
                .forEach(container -> handles.add(
                        new RunHandle(container.getLabels().get(RUN_ID_LABEL), container.getId())));
        return handles;
    }
}
```

> `units` is an in-memory map, which is fine within one run's lifetime and **not** the recovery
> mechanism — `discoverOrphans` is, and it reads labels from the daemon rather than this map. A
> restarted worker rebuilds nothing; it discovers.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-runtime-docker:test`
Expected: PASS. Requires a running Docker daemon; pulls `alpine:3.20` on first run.

- [ ] **Step 6: Commit**

```bash
git add spire-runtime-docker settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the Docker run unit: init, agent and publisher on shared volumes"
```

---

## Task 6b: `spire-publisher` — the sidecar that gates and pushes

**Files:**
- Create: `spire-publisher/build.gradle.kts`, `spire-publisher/LICENSE`, `spire-publisher/Dockerfile`
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/{PublisherMain,HandoffWatcher,OutcomeWriter}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (`serviceTestModules`), `Dockerfile`, `LICENSING.md`
- Test: `spire-publisher/src/test/java/dev/codespire/publisher/HandoffWatcherTest.java`

**Interfaces:**
- Consumes: `PublishRepo`, `ChangeSet` (Task 3); `PushGate`, `PushDecision` (Task 4).
- Produces: an image that runs as the publisher container, reading `/handoff` and writing one JSON
  line per outcome to stdout.

This is the **only** thing in the run unit holding a write credential. Its safety comes from four
properties, each of which is a test above or below: it never mounts `/workspace`, it works only in its
own clone, it never checks out a working tree, and `/handoff` is read-only to it.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoffWatcherTest {

    @Test
    void seesEachNewBundleExactlyOnce(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("1.bundle"), "x");
        watcher.poll(seen::add);
        watcher.poll(seen::add);                       // nothing new
        Files.writeString(handoff.resolve("2.bundle"), "y");
        watcher.poll(seen::add);

        assertEquals(2, seen.size(), "a bundle already handled must not be handled again");
    }

    @Test
    void ignoresPartiallyWrittenFiles(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        // The agent writes to a temp name and renames atomically; anything else is mid-write.
        Files.writeString(handoff.resolve("tmp"), "half");
        Files.writeString(handoff.resolve("3.bundle.part"), "half");
        watcher.poll(seen::add);

        assertTrue(seen.isEmpty(), "only *.bundle counts, so a half-written file is never read");
    }

    @Test
    void ordersBundlesByTheirSequenceNumber(@TempDir Path handoff) throws Exception {
        List<Path> seen = new ArrayList<>();
        HandoffWatcher watcher = new HandoffWatcher(handoff);

        Files.writeString(handoff.resolve("10.bundle"), "x");
        Files.writeString(handoff.resolve("2.bundle"), "y");
        watcher.poll(seen::add);

        assertEquals("2.bundle", seen.get(0).getFileName().toString(),
                "2 before 10 — lexical order would push the later commit first");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-publisher:test`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Create the module (four-file ritual, `serviceTestModules`)**

```kotlin
dependencies {
    implementation(project(":spire-workspace"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Plain Java with a `main`, not Quarkus — it runs for the length of one run and needs no framework.
Package it with the `application` plugin or a shadow jar, whichever the repository already uses.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches the handoff directory for bundles the agent has finished writing.
 *
 * <p>Only {@code *.bundle} counts. The agent writes to a temp name and renames atomically, so any
 * other name is mid-write and must not be read (RUN-TOPOLOGY §4.1).
 */
public final class HandoffWatcher {

    private final Path handoff;
    private final Set<String> handled = new HashSet<>();

    public HandoffWatcher(Path handoff) {
        this.handoff = handoff;
    }

    public void poll(Consumer<Path> onNewBundle) throws IOException {
        if (!Files.isDirectory(handoff)) {
            return;
        }
        List<Path> fresh;
        try (Stream<Path> entries = Files.list(handoff)) {
            fresh = entries
                    .filter(p -> p.getFileName().toString().endsWith(".bundle"))
                    .filter(p -> !handled.contains(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(HandoffWatcher::sequence))
                    .toList();
        }
        for (Path bundle : fresh) {
            handled.add(bundle.getFileName().toString());
            onNewBundle.accept(bundle);
        }
    }

    /** "2.bundle" before "10.bundle" — lexical order would ship the later commit first. */
    private static long sequence(Path bundle) {
        String name = bundle.getFileName().toString().replace(".bundle", "");
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
```

```java
package dev.codespire.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * One JSON line per outcome, on stdout. The worker reads this from the container's log stream —
 * nothing is extracted from the pod (ADR-039).
 */
public final class OutcomeWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    public void pushed(String ref, List<String> changed) {
        write(Map.of("event", "pushed", "ref", ref, "changed", changed));
    }

    public void refused(List<String> blocked, List<String> changed) {
        write(Map.of("event", "gate_refused", "blocked", blocked, "changed", changed));
    }

    public void failed(String cause, String detail) {
        write(Map.of("event", "failed", "cause", cause, "detail", detail));
    }

    private void write(Map<String, Object> payload) {
        try {
            System.out.println(JSON.writeValueAsString(payload));
            System.out.flush();
        } catch (Exception e) {
            // A publisher that cannot report is still a publisher that pushed; never crash here.
            System.out.println("{\"event\":\"failed\",\"cause\":\"REPORT_FAILED\"}");
        }
    }
}
```

`PublisherMain` reads its configuration from the environment — `SPIRE_REMOTE_URI`,
`SPIRE_BASE_COMMIT`, `SPIRE_BRANCH`, `SPIRE_PROTECTED_PATHS` (comma-separated),
`SPIRE_BUNDLE_MAX_BYTES`, `SPIRE_GIT_USERNAME`, `SPIRE_GIT_SECRET` — clones once with `PublishRepo`,
then loops:

```java
while (agentStillRunning()) {
    watcher.poll(bundle -> {
        String sha = repo.fetchBundle(bundle, maxBytes);
        ChangeSet changes = repo.changesSince(baseCommit, sha);
        PushDecision decision = PushGate.decide(changes, protectedPaths);
        if (!decision.allowed()) {
            outcome.refused(decision.blocked(), paths(changes));
            System.exit(2);                       // a gate trip TERMINATES the run
        }
        outcome.pushed(repo.pushRef(sha, branch, credential), paths(changes));
    });
    Thread.sleep(pollMillis);
}
```

**`agentStillRunning()`** is the one platform-dependent part. Where the runtime declares
`nativeSidecar`, Kubernetes terminates this container when the agent exits and the loop simply ends.
Where it does not, the agent's last act is to write `/handoff/DONE` and this method watches for it.

Three rules with no exceptions, each corresponding to a test:

- **Never mount or read `/workspace`.** Not in code, not in the image, not in the container spec.
- **Never check out a working tree.** `PublishRepo` is a bare clone; keep it that way.
- **Never write to a shared volume.** Outcomes go to stdout.

`spire-publisher/Dockerfile`: a JRE base, the jar, a non-root user, `ca-certificates`. It needs no
git binary — JGit does the work.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-publisher:test :spire-arch:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spire-publisher settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the publisher sidecar: watch, gate, push, report on stdout"
```

---

## Task 7: The run wire contract

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/event/RunResult.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/event/RunIds.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/event/RunIdsTest.java`

**Interfaces:**
- Consumes: `RepoRef`, `ScmType` (existing contract types).
- Produces: `RunCommand` sealed with `RunCommand.ExecuteRun` and `RunCommand.CancelRun`, both exposing `String runId()`. `RunResult` sealed with `RunStarted`, `RunFinished`, `RunFailed`. `RunIds.of(ScmType, String workspace, String slug, String subject, int attempt)` and `RunIds.parse(String)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunIdsTest {

    @Test
    void carriesThePlatformSoTwoScmsSharingAWorkspaceNameCannotCollide() {
        String onGitHub = RunIds.of(ScmType.GITHUB, "artyomsv", "spire-test", "finding-9", 1);
        String onGitLab = RunIds.of(ScmType.GITLAB, "artyomsv", "spire-test", "finding-9", 1);

        assertEquals("run::github:artyomsv/spire-test:finding-9:1", onGitHub);
        assertEquals("run::gitlab:artyomsv/spire-test:finding-9:1", onGitLab);
    }

    @Test
    void parsesBackWithoutAnInMemoryRegistry() {
        RunIds.Parsed parsed = RunIds.parse("run::github:artyomsv/spire-test:finding-9:2");

        assertEquals(ScmType.GITHUB, parsed.scmType());
        assertEquals("artyomsv", parsed.workspace());
        assertEquals("spire-test", parsed.slug());
        assertEquals("finding-9", parsed.subject());
        assertEquals(2, parsed.attempt());
    }

    @Test
    void refusesAMalformedIdRatherThanGuessing() {
        assertThrows(IllegalArgumentException.class, () -> RunIds.parse("run::nonsense"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-contract:test --tests '*RunIdsTest*'`
Expected: FAIL — `RunIds` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;

import java.util.Locale;

/**
 * Derives a run id from its own coordinates, so a restart loses nothing (the rule {@link ReviewIds}
 * already follows). The platform is IN the key from day one: {@code review_id} carries no provider
 * and that is a tracked defect — one workspace name registered on two SCMs sums two unrelated
 * subjects. New tables do not inherit it.
 */
public final class RunIds {

    private static final String PREFIX = "run::";

    private RunIds() {
    }

    public static String of(ScmType scmType, String workspace, String slug, String subject, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1: " + attempt);
        }
        return PREFIX + scmType.name().toLowerCase(Locale.ROOT) + ":"
                + workspace + "/" + slug + ":" + subject + ":" + attempt;
    }

    public static Parsed parse(String runId) {
        if (runId == null || !runId.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        String[] parts = runId.substring(PREFIX.length()).split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        String[] repo = parts[1].split("/");
        if (repo.length != 2) {
            throw new IllegalArgumentException("not a run id: " + runId);
        }
        return new Parsed(ScmType.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                repo[0], repo[1], parts[2], Integer.parseInt(parts[3]));
    }

    public record Parsed(ScmType scmType, String workspace, String slug, String subject, int attempt) {
    }
}
```

```java
package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.scm.RepoRef;

/**
 * Run dispatch. A SEPARATE hierarchy from {@link ActionCommand}, which declares {@code reviewId()}
 * as mandatory — a run has a {@code runId}, and a run id behind a method named {@code reviewId()} is
 * a name that lies. Rides {@code cs.run-commands}, keyed by {@code runId}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunCommand.ExecuteRun.class, name = "ExecuteRun"),
        @JsonSubTypes.Type(value = RunCommand.CancelRun.class, name = "CancelRun")
})
public sealed interface RunCommand {

    String runId();

    /**
     * Opaque, KEK-encrypted machine-account SCM credential (ADR-038) — never the review bot's.
     * Base64 Tink ciphertext, packed by the orchestrator. Never logged.
     */
    default String scmCredential() {
        return null;
    }

    /** Opaque, KEK-encrypted harness credential (ADR-031). Never logged. */
    default String harnessCredential() {
        return null;
    }

    record ExecuteRun(String runId, RepoRef repo, String baseCommit, String branch,
                      String prompt, String harness, String model, String agentImage,
                      java.util.List<String> protectedPaths,
                      long maxWallClockSeconds, String scmCredential, String harnessCredential)
            implements RunCommand {
    }

    record CancelRun(String runId, String reason) implements RunCommand {
    }
}
```

```java
package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/** What the run worker reports back on {@code cs.run-results}, keyed by {@code runId}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RunResult.RunStarted.class, name = "RunStarted"),
        @JsonSubTypes.Type(value = RunResult.RunFinished.class, name = "RunFinished"),
        @JsonSubTypes.Type(value = RunResult.RunFailed.class, name = "RunFailed")
})
public sealed interface RunResult {

    String runId();

    record RunStarted(String runId, String providerRunId) implements RunResult {}

    /** {@code pushedRef} is null when the gate refused; {@code blockedPaths} then names why. */
    record RunFinished(String runId, String pushedRef, List<String> changedPaths,
                       List<String> blockedPaths, Long inputTokens, Long outputTokens,
                       boolean usageUnknown) implements RunResult {}

    record RunFailed(String runId, String cause, String detail, boolean retryable) implements RunResult {}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :spire-contract:test`
Expected: PASS.

- [ ] **Step 5: Refresh the contract snapshot**

Run: `./gradlew :spire-contract:test --tests '*ContractSchemaSnapshotTest*'`
If it fails on the new types, regenerate the golden file the way the test's own message describes. **Then read the diff by hand**: `techdebt/spire-contract/3-2-contract-snapshot-does-not-recurse-into-nested-wire-types.md` records that the gate does not recurse into nested records, so nested shapes are invisible to it and must be reviewed manually.

- [ ] **Step 6: Commit**

```bash
git add spire-contract/src
git commit -m "Add the run wire contract with a derived, platform-carrying run id"
```

---

## Task 8: `spire-run-worker` — the stateless deployable

**Files:**
- Create: `spire-run-worker/build.gradle.kts`, `spire-run-worker/LICENSE`
- Create: `spire-run-worker/src/main/resources/application.yml`
- Create: `spire-run-worker/src/main/resources/db/migration/V1__run_claim.sql`
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/{RunDispatcher,RunLauncher,RunUnitBuilder,RunClaimStore,RunResultsEmitter,RunCommandDeserializer,HarnessRegistry,WorkerRuntimes,Credentials}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (`serviceTestModules`), `Dockerfile`, `LICENSING.md`
- Test: `spire-run-worker/src/test/java/dev/codespire/runworker/{RunClaimStoreTest,RunUnitBuilderTest}.java`

**Interfaces:**
- Consumes: `RunCommand`, `RunResult`, `RunIds` (Task 7); `HarnessAdapter` (Task 1); `CodexAdapter`
  (Task 2); `RunRuntime`, `RunUnitSpec`, `ContainerSpec`, `LogChannel` (Task 5); `DockerRunRuntime`
  (Task 6).
- Produces: `RunClaimStore.claim(String runId, String slot) -> boolean`;
  `RunUnitBuilder.build(RunCommand.ExecuteRun, HarnessAdapter) -> RunUnitSpec`;
  `RunLauncher.launch(RunCommand.ExecuteRun) -> RunResult`.

**The worker performs no git and holds no filesystem (ADR-039).** It creates the run unit, streams two
log channels, records what it sees, and emits a result. That is what makes it stateless — and
therefore what makes a run recoverable by any replica rather than only by the one that started it.

- [ ] **Step 1: Write the failing tests**

```java
package dev.codespire.runworker;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RunClaimStoreTest {

    @Inject
    RunClaimStore claims;

    @Test
    void aSecondClaimOnTheSameSlotIsRefused() {
        String runId = "run::github:acme/app:finding-1:1";

        assertTrue(claims.claim(runId, "execute"), "the first delivery does the work");
        assertFalse(claims.claim(runId, "execute"), "a redelivery must not run the agent twice");
    }

    @Test
    void aDifferentAttemptIsADifferentRunAndClaimsFreely() {
        assertTrue(claims.claim("run::github:acme/app:finding-2:1", "execute"));
        assertTrue(claims.claim("run::github:acme/app:finding-2:2", "execute"),
                "attempt 2 is a genuine second run, not a redelivery");
    }
}
```

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunUnitBuilderTest {

    private final RunUnitBuilder builder = new RunUnitBuilder();

    private RunCommand.ExecuteRun command() {
        return new RunCommand.ExecuteRun("run::github:acme/app:finding-1:1",
                new RepoRef("acme", "app"), "abc1234", "spire/run_1",
                "fix the typo", "codex", "gpt-5.6", "spire-agent-codex:1",
                List.of("deploy/**"), 3600, "enc-scm", "enc-harness");
    }

    @Test
    void theAgentGetsNoWriteCredential() {
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());

        // ADR-039: the agent physically cannot push, gate or no gate.
        assertFalse(unit.agent().environment().containsKey("SPIRE_GIT_SECRET"));
        assertTrue(unit.publisher().environment().containsKey("SPIRE_GIT_SECRET"));
    }

    @Test
    void thePublisherNeverMountsTheWorkspace() {
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());

        assertFalse(unit.publisher().mounts().containsKey("workspace"),
                "the publisher must not reach agent-controlled git config or hooks");
        assertTrue(unit.agent().mounts().containsKey("workspace"));
    }

    @Test
    void handoffIsReadOnlyToThePublisherAndWritableByTheAgent() {
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());

        assertTrue(unit.publisher().mounts().get("handoff").endsWith(":ro"));
        assertFalse(unit.agent().mounts().get("handoff").endsWith(":ro"));
    }

    @Test
    void theProtectedPathsReachThePublisherAndNotTheAgent() {
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());

        // The gate's rules come from the operator side, never from anything the agent can influence.
        assertEquals("deploy/**", unit.publisher().environment().get("SPIRE_PROTECTED_PATHS"));
        assertFalse(unit.agent().environment().containsKey("SPIRE_PROTECTED_PATHS"));
    }

    @Test
    void theInitContainerClonesWithAReadCredentialOnly() {
        RunUnitSpec unit = builder.build(command(), new CodexAdapter());

        assertTrue(unit.init().environment().containsKey("SPIRE_CLONE_SECRET"));
        assertFalse(unit.init().environment().containsKey("SPIRE_GIT_SECRET"));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :spire-run-worker:test`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Create the module, its schema and its channels**

`spire-run-worker/build.gradle.kts` — copy `spire-review-worker/build.gradle.kts` and replace the
project dependency list with:

```kotlin
    implementation(project(":spire-contract"))
    implementation(project(":spire-encryption"))
    implementation(project(":spire-harness"))
    implementation(project(":spire-harness-codex"))
    implementation(project(":spire-runtime"))
    implementation(project(":spire-runtime-docker"))
```

Note what is **absent**: `spire-workspace`. The worker runs no git. If that dependency appears, the
statelessness this task exists to establish has been lost.

`V1__run_claim.sql`:

```sql
-- The run worker's own schema (schema-per-service, ADR-011).
CREATE SCHEMA IF NOT EXISTS runworker;

-- The SOLE idempotency mechanism for run dispatch.
--
-- This worker acks a command ON RECEIPT, because an hour-long run cannot ride the review worker's
-- ordered-blocking channel. That moves the redelivery guarantee off Kafka and onto this row, and the
-- write order matters: claim FIRST, then ack. The reverse loses the command on a crash between them.
CREATE TABLE runworker.run_claim (
    run_id     TEXT        NOT NULL,
    slot       TEXT        NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, slot)
);

-- Which run unit a replica currently owns, with the heartbeat that DEFINES an orphan.
--
-- Without owner + heartbeat, discoverOrphans() cannot tell a dead replica's leak from a live
-- replica's healthy hour-long run: reap eagerly and the watchdog kills real work, reap lazily and an
-- eviction leaks forever. Note this row holds no filesystem path — since ADR-039 there is nothing on
-- any worker's disk to point at.
CREATE TABLE runworker.run_lease (
    run_id       TEXT        PRIMARY KEY,
    owner_id     TEXT        NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`application.yml` — model it on `spire-review-worker`'s, with:

```yaml
mp:
  messaging:
    incoming:
      run-commands-in:
        connector: smallrye-kafka
        topic: cs.run-commands
        group:
          id: spire-run-worker
        value:
          deserializer: dev.codespire.runworker.RunCommandDeserializer
        auto:
          offset:
            reset: latest
        failure-strategy: dead-letter-queue
        dead-letter-queue:
          topic: cs.dlq
        # Unlike the review worker, this channel acks on RECEIPT: an hour-long run would outlive any
        # sane unprocessed-record threshold, and that exact pairing once stalled a consumer that
        # re-stalled on every restart and needed a manual seek. Idempotency lives in run_claim.
        max:
          poll:
            records: 1
    outgoing:
      run-results-out:
        connector: smallrye-kafka
        topic: cs.run-results
        key:
          serializer: org.apache.kafka.common.serialization.StringSerializer
        value:
          serializer: dev.codespire.runworker.RunResultSerializer
        waitForWriteCompletion: true
```

Set `SPIRE_RUN_WORKER_HTTP_PORT` (default `34083`) alongside the other per-service port vars, and add
`docker build --build-arg SERVICE=run-worker` to the `Dockerfile` header comment. Then the four-file
ritual, with `spire-run-worker` in **`serviceTestModules`**.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.runworker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * The run worker's only idempotency mechanism. The command channel acks on receipt, so a redelivery
 * is not stopped by Kafka — it is stopped here.
 */
@ApplicationScoped
public class RunClaimStore {

    @Inject
    DataSource dataSource;

    /** @return true when THIS caller took the slot; false when it was already taken. */
    public boolean claim(String runId, String slot) {
        String sql = """
                INSERT INTO runworker.run_claim (run_id, slot)
                VALUES (?, ?)
                ON CONFLICT (run_id, slot) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, slot);
            return statement.executeUpdate() == 1;
        } catch (Exception e) {
            // Fail CLOSED: an unreadable claim table must not authorise a paid run.
            throw new IllegalStateException("could not take the run claim for " + runId, e);
        }
    }
}
```

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.runtime.ContainerSpec;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a command into the three-container run unit of ADR-039.
 *
 * <p>The security properties of the whole design are decided HERE, by what each container is handed:
 *
 * <ul>
 *   <li>the agent gets the model credential and the workspace, and NO write credential;</li>
 *   <li>the publisher gets the write credential, the gate's rules, and NOT the workspace;</li>
 *   <li>{@code /handoff} is writable by the agent and read-only to the publisher;</li>
 *   <li>the init container gets a READ-only clone credential.</li>
 * </ul>
 *
 * Each of those is asserted by a test, because each is a boundary rather than a preference.
 */
@ApplicationScoped
public class RunUnitBuilder {

    private static final String WORKSPACE = "workspace";
    private static final String HANDOFF = "handoff";
    private static final String PUBLISHER_IMAGE = "spire-publisher:latest";
    private static final long BUNDLE_MAX_BYTES = 256L * 1024 * 1024;

    public RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter adapter) {
        Credentials.Scm scm = Credentials.scm(command.scmCredential());
        Map<String, String> harnessEnv = Credentials.harnessEnv(command.harnessCredential());

        ContainerSpec init = new ContainerSpec(
                PUBLISHER_IMAGE,
                List.of("spire-clone"),
                Map.of("SPIRE_REMOTE_URI", remoteUri(command),
                        "SPIRE_BRANCH", command.branch(),
                        "SPIRE_BASE_COMMIT", command.baseCommit(),
                        "SPIRE_CLONE_USERNAME", scm.readUsername(),
                        "SPIRE_CLONE_SECRET", scm.readSecret()),
                Map.of(WORKSPACE, "/workspace"));

        HarnessInvocation invocation = new HarnessInvocation(command.runId(), command.prompt(),
                "/workspace", command.model(), harnessEnv,
                Duration.ofSeconds(command.maxWallClockSeconds()));

        Map<String, String> agentEnv = new LinkedHashMap<>(adapter.environment(invocation));
        agentEnv.put("SPIRE_BASE_COMMIT", command.baseCommit());
        agentEnv.put("SPIRE_HANDOFF", "/handoff");
        ContainerSpec agent = new ContainerSpec(
                command.agentImage(),
                adapter.command(invocation),
                Map.copyOf(agentEnv),
                Map.of(WORKSPACE, "/workspace", HANDOFF, "/handoff"));

        ContainerSpec publisher = new ContainerSpec(
                PUBLISHER_IMAGE,
                List.of("spire-publish"),
                Map.of("SPIRE_REMOTE_URI", remoteUri(command),
                        "SPIRE_BRANCH", command.branch(),
                        "SPIRE_BASE_COMMIT", command.baseCommit(),
                        "SPIRE_PROTECTED_PATHS", String.join(",", command.protectedPaths()),
                        "SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES),
                        "SPIRE_GIT_USERNAME", scm.writeUsername(),
                        "SPIRE_GIT_SECRET", scm.writeSecret()),
                Map.of(HANDOFF, "/handoff:ro"));

        return new RunUnitSpec(command.runId(), init, agent, publisher,
                4L * 1024 * 1024 * 1024, 2_000_000_000L,
                Duration.ofSeconds(command.maxWallClockSeconds()));
    }

    private String remoteUri(RunCommand.ExecuteRun command) {
        return command.repo().cloneUrl();
    }
}
```

> If `RepoRef` has no `cloneUrl()`, add one derived from its existing fields rather than
> reconstructing a URL at this call site. Read `spire-contract/.../scm/RepoRef.java` first.

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.RunEvent;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Creates the run unit and reads its two log streams. Performs no git and holds no filesystem — that
 * is the whole point of ADR-039, and it is what lets any replica salvage any run.
 */
@ApplicationScoped
public class RunLauncher {

    private static final Logger LOG = Logger.getLogger(RunLauncher.class);

    @Inject
    RunRuntime runtime;

    @Inject
    RunUnitBuilder builder;

    @Inject
    HarnessRegistry harnesses;

    @Inject
    RunEventPublisher events;

    public RunResult launch(RunCommand.ExecuteRun command) {
        HarnessAdapter adapter = harnesses.forName(command.harness());
        RunUnitSpec unit = builder.build(command, adapter);

        RunHandle handle;
        try {
            handle = runtime.create(unit);
        } catch (Exception e) {
            return new RunResult.RunFailed(command.runId(), "SANDBOX_UNREACHABLE", e.getMessage(), true);
        }

        List<RunEvent> seen = new ArrayList<>();
        PublisherOutcome outcome = new PublisherOutcome();

        // Both streams are read concurrently: the publisher reports pushes WHILE the agent is still
        // working (continuous checkpointing, RUN-TOPOLOGY §5), so reading them in sequence would
        // hold every push report until the run ended.
        CompletableFuture<Void> agentLog = CompletableFuture.runAsync(() ->
                runtime.attach(handle, LogChannel.AGENT, line ->
                        adapter.parse(line).ifPresent(event -> {
                            seen.add(event);
                            events.publish(command.runId(), event);
                        })));
        CompletableFuture<Void> publisherLog = CompletableFuture.runAsync(() ->
                runtime.attach(handle, LogChannel.PUBLISHER, outcome::accept));

        Finalization finalization = runtime.finalize(handle);
        CompletableFuture.allOf(agentLog, publisherLog).join();

        if (!finalization.salvaged()) {
            // The unit is NOT destroyed: a failed salvage must not throw away the work finalize
            // exists to keep. The watchdog or an operator reclaims it.
            return new RunResult.RunFailed(command.runId(), "SALVAGE_FAILED", finalization.detail(), false);
        }
        runtime.destroy(handle);

        if (outcome.refused()) {
            LOG.warnf("run %s refused at the push gate: %s", command.runId(), outcome.blocked());
        }
        return new RunResult.RunFinished(command.runId(), outcome.lastPushedRef(),
                outcome.changedPaths(), outcome.blocked(),
                adapter.usage(seen).map(u -> u).orElse(null) == null ? null : null,
                null, adapter.usage(seen).isEmpty());
    }
}
```

> The usage fields above are deliberately left as the UNKNOWN case. Codex runs on a subscription, so
> a missing count costs no money — but it must arrive as **UNKNOWN, never zero** (RUN-TOPOLOGY §10).
> Fill the two token fields in once Task 2 Step 6 has established what the stream actually reports.

Also write, in the same task:

- **`PublisherOutcome`** — parses the publisher's one-JSON-line-per-outcome stdout (`pushed`,
  `gate_refused`, `failed`), keeping the last pushed ref, the union of changed paths, and any blocked
  paths.
- **`RunEventPublisher`** — an `@Channel("run-events-out")` `Emitter<RunEvent>` keyed by `runId`,
  onto `cs.run-events` (the short-retention tier, ADR-034).
- **`HarnessRegistry`** — composition root, `"codex"` → `CodexAdapter`, throwing on an unknown name;
  never defaulting, because an unknown harness must fail loudly rather than run the wrong one.
- **`WorkerRuntimes`** — a CDI producer exposing `DockerRunRuntime` as the `RunRuntime` bean, since
  `spire-runtime-docker` is framework-free and carries no CDI annotations.
- **`RunDispatcher`** — `@Incoming("run-commands-in")`, `@Blocking`: claim, then run, then emit. On a
  refused claim, log and return — a redelivery is not a second run.
- **`RunCommandDeserializer`** / **`RunResultSerializer`** — copy the review worker's and change the
  type. **The never-throw behaviour on a poison record is load-bearing.**
- **`Credentials`** — Tink decrypt via `spire-encryption`, yielding a read pair and a write pair. Its
  `toString` must not render a secret.

**`HarnessRegistry` and `WorkerRuntimes` are added to the `spire-arch` allowlist in Task 11.**

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-run-worker:test`
Expected: PASS. Quarkus Dev Services boots Postgres; Flyway applies `V1`.

- [ ] **Step 6: Commit**

```bash
git add spire-run-worker settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the stateless run worker: claim before ack, no git, two log streams"
```

---

## Task 9: `llm_charge` takes a neutral subject

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V42__llm_charge_run_subject.sql`
- Modify: every read of `llm_charge` (find them with `grep -rn 'llm_charge' spire-orchestrator/src/main`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/llm/LlmChargeSubjectTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `llm_charge.subject_id`, `.subject_kind`, `.capability`, `.credential_ref`; `CallRefs.forRun(String runId, int attempt, String seq)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LlmChargeSubjectTest {

    @Inject
    DataSource dataSource;

    @Test
    void aRunChargeIsStorableWithoutPretendingToBeAReview() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens, capability)
                    VALUES (gen_random_uuid(), 'run::github:acme/app:finding-1:1', 'RUN',
                            'run:run::github:acme/app:finding-1:1:1:total', 'BUILD', 'gpt-5.6',
                            'UNMETERED', 'TOTAL', 1200, 'BUILD')
                    """);
            try (ResultSet rs = s.executeQuery(
                    "SELECT subject_kind, capability FROM llm_charge WHERE subject_kind = 'RUN'")) {
                assertTrue(rs.next());
                assertEquals("RUN", rs.getString(1));
                assertEquals("BUILD", rs.getString(2));
            }
        }
    }

    @Test
    void anUnknownKindStillFailsLoudlyAtInsert() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThrows(Exception.class, () -> s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens)
                    VALUES (gen_random_uuid(), 'run::github:acme/app:x:1', 'RUN', 'ref-x',
                            'TYPO', 'gpt-5.6', 'UNMETERED', 'TOTAL', 1)
                    """), "the kind CHECK is why a typo'd literal cannot silently enter the ledger");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*LlmChargeSubjectTest*'`
Expected: FAIL — `subject_id` does not exist; the column is `review_id`.

- [ ] **Step 3: Write the migration**

```sql
-- The ledger's spine was review-shaped: review_id NOT NULL, and a kind CHECK naming only the three
-- review call kinds. A run has a runId and none of those kinds, and putting a run id into a column
-- named review_id is the shape where a name lies (ARCHITECTURE §7).

ALTER TABLE llm_charge RENAME COLUMN review_id TO subject_id;

ALTER TABLE llm_charge ADD COLUMN subject_kind VARCHAR(8) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_subject_kind
    CHECK (subject_kind IN ('REVIEW', 'RUN'));

-- Which capability pack caused the spend. Added NOW because it cannot be backfilled: a row that did
-- not record its capability cannot have one inferred later (ADR-035).
ALTER TABLE llm_charge ADD COLUMN capability VARCHAR(16) NOT NULL DEFAULT 'REVIEW';
ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_capability
    CHECK (capability IN ('REVIEW', 'BUILD', 'AUTONOMY', 'KNOWLEDGE', 'INSIGHT'));

-- Which pool member paid, so an UNMETERED run is still attributable.
ALTER TABLE llm_charge ADD COLUMN credential_ref TEXT;

ALTER TABLE llm_charge DROP CONSTRAINT IF EXISTS llm_charge_kind_check;
ALTER TABLE llm_charge ADD CONSTRAINT llm_charge_kind_check
    CHECK (kind IN ('REVIEW', 'RECONCILE', 'FOLLOWUP', 'SPEC', 'PLAN', 'BUILD', 'FIX'));
```

> Check the real constraint name first: `\d llm_charge` in psql, or read `V30__llm_charge_ledger.sql`.
> The `DROP CONSTRAINT IF EXISTS` above must name the constraint Postgres actually created.

- [ ] **Step 4: Update every read**

Run `grep -rn 'review_id' spire-orchestrator/src/main --include=*.java | grep -i charge` and rename in each. Add to `CallRefs`:

```java
    /**
     * A run's charge identity. {@code attempt} is what distinguishes a genuine second run from a
     * redelivery — the same distinction {@link ReviewRuns} draws for reviews, and getting it backwards
     * is the difference between silently-lost money and silently-inflated money.
     */
    public static String forRun(String runId, int attempt, String seq) {
        return "run:" + runId + ":" + attempt + ":" + seq;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS — including every existing ledger test, unchanged in behaviour.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -m "Give the charge ledger a neutral subject and a capability"
```

---

## Task 10: `POST /api/runs` and the `factory_run` read model

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V43__factory_run.sql`
- Create: `spire-orchestrator/src/main/resources/db/migration/V44__scm_provider_role.sql`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/{RunResource,FactoryRunProjection,RunCommandEmitter,RunResultSaga,MachineAccounts}.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/{RunResourceTest,MachineAccountsTest}.java`

**Interfaces:**
- Consumes: `RunCommand`, `RunResult`, `RunIds` (Task 7); `ProviderRegistry`, `ProviderClients` (existing).
- Produces: `POST /api/runs` accepting `{workspace, slug, providerType, baseCommit, prompt, harness, model}` and returning `201 {runId}`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class RunResourceTest {

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void dispatchingReturnsADerivedRunId() {
        given().contentType("application/json")
                .body("""
                        {"workspace":"acme","slug":"app","providerType":"github",
                         "baseCommit":"abc1234","prompt":"fix the typo",
                         "harness":"codex","model":"gpt-5.6"}
                        """)
                .when().post("/api/runs")
                .then().statusCode(201)
                .body("runId", startsWith("run::github:acme/app:"));
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewerCannotSpendMoney() {
        given().contentType("application/json")
                .body("""
                        {"workspace":"acme","slug":"app","providerType":"github",
                         "baseCommit":"abc1234","prompt":"fix the typo",
                         "harness":"codex","model":"gpt-5.6"}
                        """)
                .when().post("/api/runs")
                .then().statusCode(403);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests '*RunResourceTest*'`
Expected: FAIL — 404, the resource does not exist.

- [ ] **Step 3a: Write the machine-account migration (FR-F29)**

The factory must not push as the review bot (ADR-038), and the existing registry cannot hold a second
credential for the same place: `scm_provider` is `UNIQUE (type, workspace)` — verified in
`V3__scm_provider.sql`. So the registry gains a role, and the constraint widens.

```sql
-- ADR-038: the factory pushes as a DEDICATED machine account, not the review bot.
--
-- Two identities, two authority sets. Allowlisting the factory's account as a PR author must not
-- give the review bot allowed-author rights on /review, /finding and /fix — which is what sharing
-- one identity would do, and is the widening ADR-036 forbids.
--
-- A role rather than a second table: same registry, same Tink encryption, same settings UI, same
-- bot-identity resolution on save. One column and a wider unique constraint.
ALTER TABLE scm_provider ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'REVIEWER';
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_role
    CHECK (role IN ('REVIEWER', 'FACTORY'));

-- Existing rows are REVIEWER by the default above, so nothing changes for a deployment that never
-- registers a factory account.
ALTER TABLE scm_provider DROP CONSTRAINT scm_provider_type_workspace_key;
ALTER TABLE scm_provider ADD CONSTRAINT scm_provider_type_workspace_role_key
    UNIQUE (type, workspace, role);
```

> Confirm the dropped constraint's real name first — Postgres generates it from the table and
> columns, and `V3` declares it inline as `UNIQUE (type, workspace)`. `\d scm_provider` shows it.

Write `MachineAccounts.resolve(ScmType, String workspace) -> Optional<ScmProvider>` returning the
`FACTORY`-role row, and its test:

```java
package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MachineAccountsTest {

    @Inject
    MachineAccounts accounts;

    @Test
    void aDeploymentWithNoFactoryAccountCannotDispatch() {
        // Failing closed here is the point: the alternative is silently pushing as the review bot,
        // whose pull requests the reviewer's own author allowlist then skips.
        assertTrue(accounts.resolve(dev.codespire.contract.port.ScmType.GITHUB, "acme").isEmpty());
    }
}
```

`RunResource` returns **`409` with a message naming the missing registration** when
`MachineAccounts.resolve` is empty. It never falls back to the reviewer credential.

- [ ] **Step 3b: Write the read-model migration**

```sql
-- The factory's read model. Archival, cost and phases arrive in later milestones; M0 needs enough
-- to answer "what happened to this run" without replaying anything.
CREATE TABLE factory_run (
    run_id          TEXT        PRIMARY KEY,
    provider_type   VARCHAR(32) NOT NULL,
    workspace       TEXT        NOT NULL,
    slug            TEXT        NOT NULL,
    subject         TEXT        NOT NULL,
    attempt         INT         NOT NULL,
    status          VARCHAR(24) NOT NULL,
    harness         VARCHAR(32) NOT NULL,
    model           TEXT        NOT NULL,
    -- ADR-038: the identity the run pushed as. Recorded, never inferred from an account name,
    -- because an account can be renamed or reassigned and an attribute written at authorship cannot.
    pushed_as       TEXT,
    pushed_ref      TEXT,
    blocked_paths   TEXT,
    failure_cause   VARCHAR(32),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    CHECK (status IN ('queued','running','succeeded','failed','push_gate_refused','cancelled'))
);

CREATE INDEX factory_run_status_idx ON factory_run (status, started_at DESC);
```

- [ ] **Step 4: Write the minimal implementation**

`RunResource` — model it on `ManualRegisterResource`: `@Path("/api/runs")`, `@RolesAllowed("spire-admin")` (dispatch spends money, so admin only — the same rule that makes re-run admin-only), a request record, `RunIds.of(...)` for the id, `factory_run` insert with `status='queued'`, then emit `RunCommand.ExecuteRun` through `RunCommandEmitter`.

`RunCommandEmitter` — `@Channel("run-commands-out")` `Emitter<RunCommand>`, keyed by `runId`, awaiting the broker ack before the resource returns 201 (the gateway does the same before its 202 — a lost dispatch must not look accepted).

`RunResultSaga` — `@Incoming("run-results-in")`, projecting `RunStarted`/`RunFinished`/`RunFailed` onto `factory_run`. `RunFinished` with a non-empty `blockedPaths` writes `status='push_gate_refused'` and stores the paths; **it is not `failed`** — the run did correct work that was deliberately not delivered, and a status that says otherwise would send an operator hunting for a bug.

Add the matching channels to `spire-orchestrator/src/main/resources/application.yml`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-orchestrator:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add spire-orchestrator/src
git commit -m "Dispatch a factory run and project its outcome"
```

---

## Task 11: Extend the neutrality scan to the new name classes

**Files:**
- Modify: `spire-arch/src/test/java/dev/codespire/arch/CoreIsProviderNeutralTest.java`
- Test: the same file (it is the test).

**Interfaces:**
- Consumes: nothing.
- Produces: the scan covers `spire-run-worker` and the harness/runtime/work-source names.

- [ ] **Step 1: Write the failing test**

Add to `CoreIsProviderNeutralTest`:

```java
    @Test
    void aShortHarnessNameDoesNotMatchTheProjectsOwnName() {
        // "pi" unanchored matches spire, api and pipeline. The existing PROVIDER_NAME pattern is
        // deliberately unanchored, so a naive extension would fail the build on this repository's
        // own module names.
        assertFalse(HARNESS_NAME.matcher("spire-orchestrator").find());
        assertFalse(HARNESS_NAME.matcher("String apiHost()").find());
        assertFalse(HARNESS_NAME.matcher("CommandDispatcher pipeline").find());

        // …while still catching a real leak.
        assertTrue(HARNESS_NAME.matcher("new PiHarness()").find());
        assertTrue(HARNESS_NAME.matcher("import dev.codespire.harness.pi.PiAdapter;").find());
        assertTrue(HARNESS_NAME.matcher("case \"pi\" ->").find());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-arch:test --tests '*CoreIsProviderNeutralTest*'`
Expected: FAIL — `HARNESS_NAME` does not exist.

- [ ] **Step 3: Write the minimal implementation**

```java
    /**
     * Harness, runtime and work-source names, with per-name match modes.
     *
     * <p>Not "the same terms" as {@link #PROVIDER_NAME}, and saying so matters: that pattern is
     * deliberately unanchored so it catches {@code githubConfig}, and an unanchored {@code pi} would
     * match {@code spire}, {@code api} and {@code pipeline} — failing the build on this project's own
     * name. Short names therefore match only in QUALIFIED forms. The reduced sensitivity is a known
     * limit, recorded rather than pretended away. An import-graph scan was rejected: the leak class
     * that motivated a text scan includes string literals, which no import graph sees.
     */
    private static final Pattern HARNESS_NAME = Pattern.compile(
            "(?i)(codex|opencode|claude-?code|kubernetes"          // long, distinctive: substring
                    + "|harness\\.pi\\b|\\bPi[A-Z]\\w*|\"pi\")");  // short: qualified forms only
```

Add `"spire-run-worker"` to `CORE_MODULES`, and allowlist the composition roots:

```java
        allowed.put("spire-run-worker/src/main/java/dev/codespire/runworker/HarnessRegistry.java",
                "Composition root: maps a harness name to its adapter. Choosing an adapter IS its job.");
        allowed.put("spire-run-worker/src/main/java/dev/codespire/runworker/WorkerRuntimes.java",
                "Composition root: produces the RunRuntime arm as a CDI bean. spire-runtime-docker is "
                        + "framework-free, so something in the worker must name it.");
```

Then run the scan for `HARNESS_NAME` across `CORE_MODULES` exactly as `PROVIDER_NAME` is run.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :spire-arch:test`
Expected: PASS. If it fails on a file that is a genuine leak, **fix the leak, do not widen the allowlist** — the SCM version of this check found three real leaks, one of which was a live defect.

- [ ] **Step 5: Mutation-verify the allowlist**

Remove the `HarnessRegistry` allowlist entry and confirm the scan **fails**. Restore it. A stale allowlist entry must also fail — verify the existing test for that still passes.

- [ ] **Step 6: Commit**

```bash
git add spire-arch/src
git commit -m "Extend the neutrality scan to harness and runtime names"
```

---

## Task 12: End-to-end — the M0 exit criteria

**Files:**
- Create: `spire-run-worker/src/test/java/dev/codespire/runworker/M0WalkingSkeletonTest.java`
- Modify: `docs/SMOKE-TEST.md` (a new Mode for the manual pass)

**Interfaces:**
- Consumes: everything above.
- Produces: proof, not code.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M0 exit criteria, BOTH halves. The first alone celebrates the ungated path, which is the
 * defect ADR-037 exists to close.
 *
 * <p>Uses a local file:// origin and an agent image whose "harness" is a shell script, so the whole
 * chain — workspace, sandbox, event stream, gate, push — is exercised with no network, no model and
 * no spend.
 */
@QuarkusTest
class M0WalkingSkeletonTest {

    @Inject
    RunExecutor executor;

    @Test
    void anOrdinaryChangeReachesTheRemote() throws Exception {
        var origin = TestOrigin.create();          // helper: builds a bare repo + a working clone
        RunCommand.ExecuteRun command = TestOrigin.executeRun(origin,
                "sh -c 'echo new > NEW.md && git add -A && git commit -m agent'");

        RunResult result = executor.execute(command);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result);
        assertNotNull(finished.pushedRef(), "the guaranteed output is a pushed branch");
        assertTrue(finished.changedPaths().contains("NEW.md"));
        assertEquals(List.of(), finished.blockedPaths());
        assertTrue(origin.hasBranch(command.branch()), "the branch must exist on the real remote");
    }

    @Test
    void aWorkflowEditIsRefusedAndNothingReachesTheRemote() throws Exception {
        var origin = TestOrigin.create();
        RunCommand.ExecuteRun command = TestOrigin.executeRun(origin,
                "sh -c 'mkdir -p .github/workflows && echo evil > .github/workflows/x.yml "
                        + "&& git add -A && git commit -m agent'");

        RunResult result = executor.execute(command);

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, result);
        assertNull(finished.pushedRef(), "a refused push must not deliver anything");
        assertEquals(List.of(".github/workflows/x.yml"), finished.blockedPaths());
        assertTrue(!origin.hasBranch(command.branch()), "nothing reached the remote");
    }

    @Test
    void aRedeliveredCommandDoesNotRunTheAgentTwice() {
        // Claim-then-ack means Kafka does not stop a redelivery — run_claim does.
        String runId = "run::github:acme/app:finding-77:1";
        assertTrue(claims.claim(runId, "execute"));
        assertTrue(!claims.claim(runId, "execute"));
    }

    @Inject
    RunClaimStore claims;
}
```

Write the `TestOrigin` helper alongside it: `create()` builds a bare origin plus an initial commit
(the same `ProcessBuilder` pattern as `WorkspaceTest`), `executeRun(origin, script)` builds an
`ExecuteRun` pointing at `alpine/git:latest` with the script as the harness command and
`protectedPaths` empty, and `hasBranch(name)` shells `git ls-remote`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-run-worker:test --tests '*M0WalkingSkeletonTest*'`
Expected: FAIL — `TestOrigin` does not exist.

- [ ] **Step 3: Implement the helper and make the tests pass**

Implement `TestOrigin`. Where the executor needs a real `HarnessAdapter` for a shell script, register
a test-scoped `ScriptHarness` implementing `HarnessAdapter` whose `command()` returns the script's
argv verbatim and whose `usage()` returns `Optional.empty()` — proving that an unreported usage
arrives as UNKNOWN through the whole chain.

- [ ] **Step 4: Run the full verification loop**

Run:

```bash
./gradlew testFast
./gradlew testServices
```

Expected: PASS, with counts higher than the pre-M0 baseline recorded in `CLAUDE.md`
(1735 Java tests across 218 suites on the PR path). Record the new counts.

- [ ] **Step 5: Manual pass against a real repository**

Follow the new `docs/SMOKE-TEST.md` mode you add in Step 6: dispatch a run against a scratch GitHub
repository using a real Codex credential and the real `spire-agent-codex` image, and confirm both
exit criteria. Then verify by hand:

```bash
docker inspect <container> | grep -i -E 'OPENAI|TOKEN|password'   # expect: nothing
docker image history spire-agent-codex | grep -i -E 'key|token'   # expect: nothing
```

A credential visible in either place fails the milestone regardless of the tests.

- [ ] **Step 6: Update the docs in the same commit**

- `docs/SMOKE-TEST.md`: a new mode covering the manual pass above.
- `CLAUDE.md`: a status bullet for M0 with the measured test counts.
- `docs/factory/ROADMAP.md`: mark M0 delivered; note anything the build taught that the design got wrong.

- [ ] **Step 7: Commit**

```bash
git add spire-run-worker/src docs CLAUDE.md
git commit -m "Prove both M0 exit criteria end to end"
```

---

## Spec coverage

| Requirement | Task |
|---|---|
| FR-F1 dispatch a run | 10 |
| FR-F2 isolated workspace | 6 (the init container clones it), 3 (the publisher's own copy) |
| FR-F3 sandboxed execution | 6 |
| FR-F4 guaranteed (gated) output | 3, 4, 6b |
| FR-F7 salvage before teardown | 5 (`Finalization`), 6, 8 |
| FR-F11 harness registry | 1 (SPI), 8 (`HarnessRegistry`) |
| FR-F13 bring-your-own image | 7, 8 — **the M0 half only** |
| FR-F28 push gate | 4 (the matcher), 6b (where it runs), 8 (the rules that reach it) |
| FR-F29 factory identity | 10 (registration), 8 (which container gets the write token) |

**FR-F13 is split across milestones and the PRD's tag does not say so.** M0 delivers the half the
walking skeleton needs: `agentImage` is a per-run parameter carried on `ExecuteRun` and honoured by
the runtime, and it accepts a digest reference so an air-gapped mirror works. The **image contract and
`spire agent-image verify`** are M1, as `ROADMAP.md` lists them. Note this in `PRD.md` §6 when M0
lands rather than leaving two documents disagreeing.

## Notes for the executor

**Two claims in this plan are grounded in documentation rather than a captured run.** Task 2's Codex
NDJSON `type` values, and Task 6's docker-java and JGit versions. Each has a verification step that
says so. Fix the code to match reality and update the test fixtures — do not adjust reality to match
the plan.

**If a task reveals the design is wrong, stop and say so.** Three errors in the design documents were
found while writing this plan (a `Forge` type that does not exist, a `PathGlobs` class that does the
opposite job, and an `ActionCommand` hierarchy that mandates `reviewId()`). The documents were
corrected. That is the expected outcome of grounding, not a failure of it.
