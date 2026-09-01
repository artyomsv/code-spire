# Factory M0 — Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A dispatched run clones a repository into a Docker sandbox, drives `codex exec` inside it, and pushes the agent's commit to a real remote as a dedicated machine account — refusing the push when the branch touches a CI-configuration file.

**Architecture:** One new deployable (`spire-run-worker`) consuming a new `cs.run-commands` topic, plus five new Apache-2.0 modules behind two seams (`HarnessAdapter`, `RunRuntime`). The orchestrator gains `POST /api/runs` and a `factory_run` read model. Nothing existing changes behaviour; the review pipeline is untouched.

**Tech Stack:** Java 25 · Quarkus 3.38.3 · Gradle Kotlin DSL · SmallRye Reactive Messaging (Kafka) · Postgres + Flyway · JGit · docker-java · JUnit 5 · Testcontainers (via Quarkus Dev Services)

**Spec:** [`docs/factory/`](../../factory/README.md) — PRD FR-F1..F4, F11, F13, F28, F29; ROADMAP §M0. Decisions ADR-028..ADR-037 in [`docs/DECISIONS.md`](../../DECISIONS.md).

---

## Global Constraints

Every task's requirements implicitly include this section.

**Adding a module is a four-file ritual. Three build guards fail otherwise, and they are not advisory:**

1. `settings.gradle.kts` — `include("spire-<name>")`
2. root `build.gradle.kts` — add to `fastTestModules` **or** `serviceTestModules`. `TestTierCoverageTest` fails the build for a module in neither.
3. `Dockerfile` — `COPY spire-<name>/build.gradle.kts spire-<name>/` in the alphabetical block. `ImageBuildSeesEveryModuleTest` fails otherwise, and without it **every production image build breaks**.
4. `<module>/LICENSE` — Apache-2.0 for libraries and adapters, FSL-1.1-ALv2 for deployables (ADR-021), plus a row in `LICENSING.md`.

**Licence boundary (ADR-021), build-enforced by intent:** no Apache-2.0 module may depend on a service module. `spire-harness`, `spire-harness-codex`, `spire-runtime`, `spire-runtime-docker`, `spire-workspace` are Apache-2.0. `spire-run-worker` is FSL.

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
| `spire-workspace/…/Workspace.java` | clone at a commit, branch, changed paths, push |
| `spire-workspace/…/PushGate.java` | protected-path refusal, CI floor |
| `spire-runtime/…/RunRuntime.java` | the SPI: create, attach, cancel, finalize, destroy |
| `spire-runtime/…/RuntimeCapabilities.java` | declared flags incl. `innerSandbox` |
| `spire-runtime-docker/…/DockerRunRuntime.java` | one sibling container per run |
| `spire-runtime-docker/…/LandlockProbe.java` | boot probe for the inner sandbox |
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
                                Duration wallClock, boolean innerSandboxAvailable) {
}
```

```java
package dev.codespire.harness;

import java.time.Instant;

/**
 * The normalized run-event vocabulary. High-volume and deliberately NOT in spire-contract: most of
 * these never reach the durable domain log (ADR-033), and putting them in the contract module would
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

    private HarnessInvocation invocation(boolean innerSandbox) {
        return new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of("OPENAI_API_KEY", "sk-secret"), Duration.ofMinutes(30), innerSandbox);
    }

    @Test
    void unattendedCommandNeverAsksForApproval() {
        List<String> argv = adapter.command(invocation(true));

        assertEquals("codex", argv.get(0));
        assertEquals("exec", argv.get(1));
        assertTrue(argv.contains("--json"), "the worker parses NDJSON, not prose");
        assertTrue(argv.contains("--ask-for-approval"), "unattended runs must not wait on a prompt");
        assertEquals("never", argv.get(argv.indexOf("--ask-for-approval") + 1));
        assertEquals("workspace-write", argv.get(argv.indexOf("--sandbox") + 1));
        // The prompt is untrusted text from a tracker: it is a separate argv element, never
        // interpolated into a shell string.
        assertTrue(argv.contains("fix the bug"));
    }

    @Test
    void disablesTheInnerSandboxWhenTheHostCannotProvideIt() {
        List<String> argv = adapter.command(invocation(false));

        assertEquals("danger-full-access", argv.get(argv.indexOf("--sandbox") + 1),
                "with no Landlock the container is the only boundary; pretending otherwise fails the run");
    }

    @Test
    void parsesOneNdjsonLinePerEvent() {
        Optional<RunEvent> thinking = adapter.parse(
                "{\"type\":\"agent_reasoning\",\"text\":\"checking the parser\"}");
        Optional<RunEvent> tool = adapter.parse(
                "{\"type\":\"exec_command_begin\",\"command\":[\"bash\",\"-lc\",\"ls\"]}");
        Optional<RunEvent> noise = adapter.parse("not json at all");

        assertTrue(thinking.orElseThrow() instanceof RunEvent.Thinking);
        assertTrue(tool.orElseThrow() instanceof RunEvent.ToolUse);
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
 * <p>Auth is an API key or a subscription credential the operator registered (ADR-030); this adapter
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
        // Landlock is host-dependent (EXECUTION-LAYER §5.1). With it, two boundaries; without it,
        // the container is the only one and asking Codex to sandbox itself would fail the run.
        String sandbox = invocation.innerSandboxAvailable() ? "workspace-write" : "danger-full-access";
        return List.of(
                "codex", "exec",
                "--json",
                "--sandbox", sandbox,
                "--ask-for-approval", "never",
                "--model", invocation.model(),
                "--cd", invocation.workspacePath(),
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
        String type = node.path("type").asText("");
        Instant at = Instant.now();
        return switch (type) {
            case "agent_reasoning" -> Optional.of(new RunEvent.Thinking(at, node.path("text").asText("")));
            case "agent_message" -> Optional.of(new RunEvent.Output(at, node.path("text").asText("")));
            case "exec_command_begin" -> Optional.of(
                    new RunEvent.ToolUse(at, "bash", node.path("command").toString()));
            case "exec_command_end" -> Optional.of(new RunEvent.ToolResult(at, "bash",
                    node.path("exit_code").asInt(0) != 0, node.path("stdout").asText("")));
            case "token_count" -> usageEvent(node, at);
            default -> Optional.of(new RunEvent.StateChange(at, type, ""));
        };
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

- [ ] **Step 6: Verify the NDJSON shapes against the real CLI, and correct the adapter if they differ**

Run:

```bash
docker run --rm -e OPENAI_API_KEY -v "$PWD:/w" -w /w node:22-alpine \
  sh -c 'npm i -g @openai/codex >/dev/null 2>&1 && codex exec --json --sandbox read-only --ask-for-answer never "say hi"' \
  | head -20
```

Expected: newline-delimited JSON objects. **The `type` values in Step 4 are from documentation, not from a captured run.** If they differ, fix `parse` and update the test fixtures to the observed lines — the test's job is to pin the real wire shape, not a guessed one.

- [ ] **Step 7: Commit**

```bash
git add spire-harness-codex settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the Codex harness adapter with NDJSON event parsing"
```

---

## Task 3: `spire-workspace` — clone, branch, changed paths, push

**Files:**
- Create: `spire-workspace/build.gradle.kts`, `spire-workspace/LICENSE`
- Create: `spire-workspace/src/main/java/dev/codespire/workspace/{Workspace,WorkspaceSpec,ChangeSet,ChangedPath,ChangeKind,PushCredential}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`
- Test: `spire-workspace/src/test/java/dev/codespire/workspace/WorkspaceTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Workspace.create(WorkspaceSpec) -> Workspace`; `Workspace.path() -> Path`; `Workspace.changes() -> ChangeSet`; `Workspace.push(String branch, PushCredential) -> String` returning the pushed ref. `ChangeSet.paths() -> List<ChangedPath>`; `ChangedPath(String path, ChangeKind kind)`; `ChangeKind` = `ADDED|MODIFIED|DELETED|RENAMED_FROM|RENAMED_TO`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceTest {

    /** Builds a throwaway origin repo on disk — no network, no fixtures to keep in sync. */
    private Path origin(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("origin");
        Files.createDirectories(repo);
        run(repo, "git", "init", "--initial-branch=main");
        run(repo, "git", "config", "user.email", "t@example.invalid");
        run(repo, "git", "config", "user.name", "t");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        Files.createDirectories(repo.resolve(".github/workflows"));
        Files.writeString(repo.resolve(".github/workflows/ci.yml"), "on: push\n");
        run(repo, "git", "add", ".");
        run(repo, "git", "commit", "-m", "initial");
        return repo;
    }

    private void run(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", argv));
    }

    @Test
    void clonesAtAnExplicitCommitAndCreatesItsOwnBranch(@TempDir Path dir) throws Exception {
        Path origin = origin(dir);
        String head = head(origin);

        Workspace ws = Workspace.create(new WorkspaceSpec(
                origin.toUri().toString(), head, "spire/run_abc", dir.resolve("ws"), null));

        assertTrue(Files.exists(ws.path().resolve("README.md")));
        assertEquals("spire/run_abc", ws.branch());
    }

    @Test
    void reportsEveryChangedPathIncludingDeletionsAndBothSidesOfARename(@TempDir Path dir) throws Exception {
        Path origin = origin(dir);
        Workspace ws = Workspace.create(new WorkspaceSpec(
                origin.toUri().toString(), head(origin), "spire/run_abc", dir.resolve("ws"), null));

        // Simulate what an agent does inside the sandbox.
        Files.writeString(ws.path().resolve("NEW.md"), "new\n");
        Files.delete(ws.path().resolve(".github/workflows/ci.yml"));
        Files.move(ws.path().resolve("README.md"), ws.path().resolve("DOCS.md"));
        run(ws.path(), "git", "config", "user.email", "t@example.invalid");
        run(ws.path(), "git", "config", "user.name", "t");
        run(ws.path(), "git", "add", "-A");
        run(ws.path(), "git", "commit", "-m", "agent work");

        Set<String> paths = ws.changes().paths().stream()
                .map(ChangedPath::path).collect(Collectors.toSet());

        assertTrue(paths.contains("NEW.md"));
        assertTrue(paths.contains(".github/workflows/ci.yml"), "a deleted workflow must still be seen");
        assertTrue(paths.contains("README.md"), "the rename's source side must be seen");
        assertTrue(paths.contains("DOCS.md"), "the rename's target side must be seen");
    }

    @Test
    void reportsNoChangesWhenTheAgentCommittedNothing(@TempDir Path dir) throws Exception {
        Path origin = origin(dir);
        Workspace ws = Workspace.create(new WorkspaceSpec(
                origin.toUri().toString(), head(origin), "spire/run_abc", dir.resolve("ws"), null));

        assertEquals(List.of(), ws.changes().paths());
    }

    private String head(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repo.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor());
        return out;
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
    // JGit rather than shelling out: the worker image then needs no git binary, and clone/diff/push
    // are testable in-process against a local file:// origin with no network.
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

If that JGit version fails to resolve, run `./gradlew :spire-workspace:dependencies --refresh-dependencies` and pin the newest published `7.x` from Maven Central. Do not leave it unpinned.

Then the four-file ritual.

- [ ] **Step 4: Write the minimal implementation**

```java
package dev.codespire.workspace;

import java.nio.file.Path;

/**
 * What a run's workspace is made of. {@code branch} is created when it does not exist and checked
 * out when it does — FR-F2: the workspace is always fresh, the branch is not always new (a fix run
 * targeting an open pull request checks out that pull request's source branch).
 */
public record WorkspaceSpec(String remoteUri, String baseCommit, String branch,
                            Path directory, PushCredential credential) {
}
```

```java
package dev.codespire.workspace;

/** Credentials for a git remote. Never rendered into a URL — a URL reaches the run event stream. */
public record PushCredential(String username, String secret) {

    @Override
    public String toString() {
        return "PushCredential[username=" + username + ", secret=***]";
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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A run's git workspace: a fresh clone at an explicit base commit, on a named branch.
 *
 * <p>Rename detection is ON, and BOTH sides of a rename are reported. A rename that moves a file
 * into a protected path is the obvious way to evade a push gate that only looks at the target.
 */
public final class Workspace implements AutoCloseable {

    private final Git git;
    private final String baseCommit;
    private final String branch;
    private final Path directory;

    private Workspace(Git git, String baseCommit, String branch, Path directory) {
        this.git = git;
        this.baseCommit = baseCommit;
        this.branch = branch;
        this.directory = directory;
    }

    public static Workspace create(WorkspaceSpec spec) throws Exception {
        Git git = Git.cloneRepository()
                .setURI(spec.remoteUri())
                .setDirectory(spec.directory().toFile())
                .setNoCheckout(true)
                .setCredentialsProvider(provider(spec.credential()))
                .call();
        git.checkout()
                .setName(spec.branch())
                .setCreateBranch(true)
                .setStartPoint(spec.baseCommit())
                .call();
        return new Workspace(git, spec.baseCommit(), spec.branch(), spec.directory());
    }

    public Path path() {
        return directory;
    }

    public String branch() {
        return branch;
    }

    /** Every path the branch changed relative to its base commit. */
    public ChangeSet changes() throws IOException {
        Repository repo = git.getRepository();
        try (var reader = repo.newObjectReader(); var walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            CanonicalTreeParser base = new CanonicalTreeParser();
            base.reset(reader, walk.parseCommit(ObjectId.fromString(baseCommit)).getTree());
            CanonicalTreeParser head = new CanonicalTreeParser();
            head.reset(reader, walk.parseCommit(repo.resolve("HEAD")).getTree());

            List<ChangedPath> paths = new ArrayList<>();
            for (DiffEntry entry : git.diff().setOldTree(base).setNewTree(head).call()) {
                switch (entry.getChangeType()) {
                    case ADD -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.ADDED));
                    case MODIFY -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.MODIFIED));
                    case DELETE -> paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.DELETED));
                    case RENAME, COPY -> {
                        paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.RENAMED_FROM));
                        paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.RENAMED_TO));
                    }
                }
            }
            return new ChangeSet(List.copyOf(paths));
        } catch (Exception e) {
            throw new IOException("could not diff " + branch + " against " + baseCommit, e);
        }
    }

    /** @return the pushed ref. Call ONLY after the push gate has passed. */
    public String push(PushCredential credential) throws Exception {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                .setCredentialsProvider(provider(credential))
                .call();
        return "refs/heads/" + branch;
    }

    private static UsernamePasswordCredentialsProvider provider(PushCredential credential) {
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

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-workspace:test :spire-arch:test`
Expected: PASS. `git` must be on the test machine's PATH — the test builds its origin repo with it. Production does not need git; JGit does the work.

- [ ] **Step 6: Commit**

```bash
git add spire-workspace settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the run workspace: clone at a commit, branch, changed paths"
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
 * <p><b>A floor, not a setting (ADR-036).</b> A pushed branch executes its own CI workflow files on
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

## Task 5: `spire-runtime` — the placement SPI and the Landlock probe

**Files:**
- Create: `spire-runtime/build.gradle.kts`, `spire-runtime/LICENSE`
- Create: `spire-runtime/src/main/java/dev/codespire/runtime/{RuntimeType,RuntimeCapabilities,RunSpec,RunHandle,Finalization,RunRuntime}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`, `PureModulesAreFrameworkFreeTest`
- Test: `spire-runtime/src/test/java/dev/codespire/runtime/FinalizationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `RunRuntime` with `RuntimeType type()`, `RuntimeCapabilities capabilities()`, `RunHandle create(RunSpec)`, `void attach(RunHandle, Consumer<String> lines)`, `void cancel(RunHandle)`, `Finalization finalize(RunHandle)`, `void destroy(RunHandle)`, `List<RunHandle> discoverOrphans()`. `Finalization(int exitCode, boolean salvaged, String detail)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalizationTest {

    @Test
    void aFailedSalvageIsNotASuccessfulRun() {
        Finalization failed = Finalization.salvageFailed("push rejected by branch protection");

        assertFalse(failed.salvaged(),
                "destroy must not proceed on a failed salvage — that is the loss finalize prevents");
        assertTrue(failed.detail().contains("branch protection"));
    }

    @Test
    void capabilitiesDeclareTheInnerSandboxRatherThanAssumingIt() {
        RuntimeCapabilities withLandlock = new RuntimeCapabilities(true, true, false, true, true, true);
        RuntimeCapabilities without = new RuntimeCapabilities(true, true, false, true, true, false);

        assertTrue(withLandlock.innerSandbox());
        assertFalse(without.innerSandbox());
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

/**
 * What a runtime can do. The domain reads these; it never branches on {@link RuntimeType}.
 *
 * <p>{@code innerSandbox} is PROBED at boot, not assumed: Landlock is host-kernel-dependent, and a
 * silently missing defence layer is the failure this project keeps paying for (EXECUTION-LAYER §5.1).
 */
public record RuntimeCapabilities(boolean networkPolicy, boolean resourceLimits, boolean steering,
                                  boolean archival, boolean garbageCollection, boolean innerSandbox) {
}
```

```java
package dev.codespire.runtime;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One workload. {@code environment} carries injected credentials and MUST NOT be logged; the docker
 * arm additionally keeps them out of container labels, which `docker inspect` prints.
 */
public record RunSpec(String runId, String image, List<String> argv, Map<String, String> environment,
                      String workspaceHostPath, String workspaceMountPath,
                      long memoryBytes, long nanoCpus, Duration wallClock) {
}
```

```java
package dev.codespire.runtime;

/** An opaque handle to a live workload. {@code providerRunId} is a container id or a pod name. */
public record RunHandle(String runId, String providerRunId) {
}
```

```java
package dev.codespire.runtime;

/**
 * The result of finalizing a run — salvage, BEFORE teardown.
 *
 * <p>{@code destroy} runs only when {@link #salvaged()} is true. A failed salvage preserves the
 * workspace, because "the agent did the work and the container died with it" was the second most
 * common failure in the prior art this design learned from.
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
 * Where a workload runs and how its life is controlled.
 *
 * <p>{@link #finalize} and {@link #destroy} are separate on purpose. Merging them is how completed
 * work gets thrown away.
 */
public interface RunRuntime {

    RuntimeType type();

    RuntimeCapabilities capabilities();

    RunHandle create(RunSpec spec);

    /** Streams the workload's stdout line by line until it exits. */
    void attach(RunHandle handle, Consumer<String> lines);

    void cancel(RunHandle handle);

    Finalization finalize(RunHandle handle);

    void destroy(RunHandle handle);

    /** Workloads this runtime holds that no live lease claims. See ARCHITECTURE §7. */
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

## Task 6: `spire-runtime-docker` — one sibling container per run

**Files:**
- Create: `spire-runtime-docker/build.gradle.kts`, `spire-runtime-docker/LICENSE`
- Create: `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/{DockerRunRuntime,LandlockProbe}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `Dockerfile`, `LICENSING.md`
- Test: `spire-runtime-docker/src/test/java/dev/codespire/runtime/docker/DockerRunRuntimeIT.java`

**Interfaces:**
- Consumes: `RunRuntime`, `RunSpec`, `RunHandle`, `Finalization`, `RuntimeCapabilities`, `RuntimeType` from Task 5.
- Produces: `DockerRunRuntime` implementing `RunRuntime`; `LandlockProbe.available(DockerClient, String image) -> boolean`.

- [ ] **Step 1: Write the failing test**

```java
package dev.codespire.runtime.docker;

import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration test: needs a working Docker daemon. Part of testFast — it is fast, not pure. */
class DockerRunRuntimeIT {

    private final DockerRunRuntime runtime = new DockerRunRuntime();

    @Test
    void runsAWorkloadAndStreamsItsOutput(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("marker.txt"), "present\n");

        RunSpec spec = new RunSpec("run_test1", "alpine:3.20",
                List.of("sh", "-c", "cat /workspace/marker.txt; echo done"),
                Map.of(), workspace.toString(), "/workspace",
                256L * 1024 * 1024, 1_000_000_000L, Duration.ofMinutes(1));

        RunHandle handle = runtime.create(spec);
        List<String> lines = new ArrayList<>();
        runtime.attach(handle, lines::add);
        Finalization finalization = runtime.finalize(handle);
        runtime.destroy(handle);

        assertEquals(0, finalization.exitCode());
        assertTrue(finalization.salvaged());
        assertTrue(lines.contains("present"), "the workspace must be mounted and readable");
        assertTrue(lines.contains("done"));
    }

    @Test
    void reportsANonZeroExitRatherThanThrowing(@TempDir Path workspace) {
        RunSpec spec = new RunSpec("run_test2", "alpine:3.20", List.of("sh", "-c", "exit 3"),
                Map.of(), workspace.toString(), "/workspace",
                256L * 1024 * 1024, 1_000_000_000L, Duration.ofMinutes(1));

        RunHandle handle = runtime.create(spec);
        runtime.attach(handle, line -> { });
        Finalization finalization = runtime.finalize(handle);
        runtime.destroy(handle);

        assertEquals(3, finalization.exitCode());
    }

    @Test
    void capabilitiesReportTheProbedInnerSandbox() {
        // Whatever the answer on this host, it must be a probe result and not a hard-coded literal.
        boolean probed = LandlockProbe.available(runtime.client(), "alpine:3.20");

        assertEquals(probed, runtime.capabilities().innerSandbox());
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
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.command.WaitContainerResultCallback;

/**
 * Asks the host whether Landlock is usable inside a container, by calling
 * {@code landlock_create_ruleset(NULL, 0, LANDLOCK_CREATE_RULESET_VERSION)} — syscall 444 on x86_64.
 *
 * <p>A positive return is the ABI version. Measured on Docker 29.6 / WSL2 kernel 6.18 this returns 7
 * under the DEFAULT seccomp profile. It is host-dependent, which is exactly why it is probed.
 */
final class LandlockProbe {

    private LandlockProbe() {
    }

    static boolean available(DockerClient client, String probeImage) {
        String script = "python3 - <<'PY'\n"
                + "import ctypes\n"
                + "libc = ctypes.CDLL(None, use_errno=True)\n"
                + "libc.syscall.restype = ctypes.c_long\n"
                + "libc.syscall.argtypes = [ctypes.c_long, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32]\n"
                + "raise SystemExit(0 if libc.syscall(444, None, 0, 1) > 0 else 1)\n"
                + "PY\n";
        try {
            var created = client.createContainerCmd(probeImage)
                    .withCmd("sh", "-c", "command -v python3 >/dev/null && " + script)
                    .withHostConfig(HostConfig.newHostConfig().withAutoRemove(false))
                    .exec();
            client.startContainerCmd(created.getId()).exec();
            int exit = client.waitContainerCmd(created.getId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            client.removeContainerCmd(created.getId()).withForce(true).exec();
            return exit == 0;
        } catch (Exception e) {
            return false; // an unanswerable probe is ABSENT, never assumed present
        }
    }
}
```

```java
package dev.codespire.runtime.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One sibling container per run, over the Docker socket.
 *
 * <p><b>Socket access is root-equivalent on the host.</b> That is stated in SECURITY.md rather than
 * mitigated away; the Kubernetes arm removes it.
 */
public final class DockerRunRuntime implements RunRuntime {

    /** The run id is a container LABEL so the orphan watchdog can find what a restart forgot. */
    static final String RUN_ID_LABEL = "dev.codespire.runId";

    private final DockerClient client;
    private final RuntimeCapabilities capabilities;

    public DockerRunRuntime() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.client = DockerClientImpl.getInstance(config, http);
        boolean innerSandbox = LandlockProbe.available(client, "alpine:3.20");
        this.capabilities = new RuntimeCapabilities(false, true, false, true, true, innerSandbox);
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
        return capabilities;
    }

    @Override
    public RunHandle create(RunSpec spec) {
        List<String> env = new ArrayList<>();
        for (Map.Entry<String, String> entry : spec.environment().entrySet()) {
            env.add(entry.getKey() + "=" + entry.getValue());
        }
        HostConfig host = HostConfig.newHostConfig()
                .withBinds(Bind.parse(spec.workspaceHostPath() + ":" + spec.workspaceMountPath()))
                .withMemory(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withAutoRemove(false); // finalize must be able to read the exit code
        var created = client.createContainerCmd(spec.image())
                .withCmd(spec.argv())
                .withEnv(env)                       // credentials live here, never in a label
                .withLabels(Map.of(RUN_ID_LABEL, spec.runId()))
                .withWorkingDir(spec.workspaceMountPath())
                .withHostConfig(host)
                .exec();
        client.startContainerCmd(created.getId()).exec();
        return new RunHandle(spec.runId(), created.getId());
    }

    @Override
    public void attach(RunHandle handle, Consumer<String> lines) {
        try {
            client.logContainerCmd(handle.providerRunId())
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
        client.killContainerCmd(handle.providerRunId()).exec();
    }

    @Override
    public Finalization finalize(RunHandle handle) {
        try {
            int exit = client.waitContainerCmd(handle.providerRunId())
                    .exec(new WaitContainerResultCallback()).awaitStatusCode();
            return Finalization.salvaged(exit, "container exited");
        } catch (Exception e) {
            return Finalization.salvageFailed("could not read the exit code: " + e.getMessage());
        }
    }

    @Override
    public void destroy(RunHandle handle) {
        try {
            client.removeContainerCmd(handle.providerRunId()).withForce(true).exec();
        } catch (Exception e) {
            // Already gone is not a failure; a leaked container is the watchdog's problem.
        }
    }

    @Override
    public List<RunHandle> discoverOrphans() {
        List<RunHandle> handles = new ArrayList<>();
        client.listContainersCmd().withShowAll(true)
                .withLabelFilter(List.of(RUN_ID_LABEL))
                .exec()
                .forEach(container -> handles.add(
                        new RunHandle(container.getLabels().get(RUN_ID_LABEL), container.getId())));
        return handles;
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-runtime-docker:test`
Expected: PASS. Requires a running Docker daemon and pulls `alpine:3.20` on first run.

- [ ] **Step 6: Commit**

```bash
git add spire-runtime-docker settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the Docker run runtime with a probed inner sandbox"
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
     * Opaque, KEK-encrypted machine-account SCM credential (ADR-037) — never the review bot's.
     * Base64 Tink ciphertext, packed by the orchestrator. Never logged.
     */
    default String scmCredential() {
        return null;
    }

    /** Opaque, KEK-encrypted harness credential (ADR-030). Never logged. */
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

## Task 8: `spire-run-worker` — the deployable

**Files:**
- Create: `spire-run-worker/build.gradle.kts`, `spire-run-worker/LICENSE`
- Create: `spire-run-worker/src/main/resources/application.yml`
- Create: `spire-run-worker/src/main/resources/db/migration/V1__run_claim.sql`
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/{RunDispatcher,RunExecutor,RunClaimStore,RunResultsEmitter,RunCommandDeserializer,HarnessRegistry}.java`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (`serviceTestModules`), `Dockerfile`, `LICENSING.md`
- Test: `spire-run-worker/src/test/java/dev/codespire/runworker/RunClaimStoreTest.java`

**Interfaces:**
- Consumes: `RunCommand`, `RunResult`, `RunIds` (Task 7); `HarnessAdapter` (Task 1); `CodexAdapter` (Task 2); `Workspace`, `PushGate` (Tasks 3–4); `RunRuntime`, `DockerRunRuntime` (Tasks 5–6).
- Produces: `RunClaimStore.claim(String runId, String slot) -> boolean` (false when already claimed); `RunExecutor.execute(RunCommand.ExecuteRun) -> RunResult`.

- [ ] **Step 1: Write the failing test**

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

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :spire-run-worker:test`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Create the module, its schema and its channels**

`spire-run-worker/build.gradle.kts` — copy `spire-review-worker/build.gradle.kts` and replace the dependency block's project list with:

```kotlin
    implementation(project(":spire-contract"))
    implementation(project(":spire-encryption"))
    implementation(project(":spire-harness"))
    implementation(project(":spire-harness-codex"))
    implementation(project(":spire-runtime"))
    implementation(project(":spire-runtime-docker"))
    implementation(project(":spire-workspace"))
```

keeping the Quarkus extension list (jackson, messaging-kafka, jdbc-postgresql, flyway, config-yaml, smallrye-health, logging-json, oidc) and the test block.

`V1__run_claim.sql`:

```sql
-- The run worker's own schema (schema-per-service, ADR-011).
CREATE SCHEMA IF NOT EXISTS runworker;

-- The SOLE idempotency mechanism for run dispatch.
--
-- The run worker acks a command ON RECEIPT, because an hour-long run cannot ride the review
-- worker's ordered-blocking channel. That moves the redelivery guarantee off Kafka and onto this
-- row, and the write order matters: claim FIRST, then ack. The reverse loses the command on a
-- crash between the two.
CREATE TABLE runworker.run_claim (
    run_id     TEXT        NOT NULL,
    slot       TEXT        NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, slot)
);

-- Which sandbox owns which workspace, with the heartbeat that DEFINES an orphan.
--
-- Without owner + heartbeat, discoverOrphans() cannot tell a dead replica's leak from a live
-- replica's healthy hour-long run: reap eagerly and the watchdog kills real work, reap lazily and
-- an eviction leaks forever.
CREATE TABLE runworker.workspace_lease (
    run_id        TEXT        PRIMARY KEY,
    owner_id      TEXT        NOT NULL,
    workspace_dir TEXT        NOT NULL,
    heartbeat_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`application.yml` — model it on `spire-review-worker/src/main/resources/application.yml`, with:

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

Set the HTTP port env var alongside the others (`SPIRE_RUN_WORKER_HTTP_PORT`, default `34083`).

Then the four-file ritual, with `spire-run-worker` in **`serviceTestModules`**, and add
`docker build --build-arg SERVICE=run-worker` to the `Dockerfile` header comment.

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
import dev.codespire.contract.event.RunResult;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunSpec;
import dev.codespire.workspace.PushCredential;
import dev.codespire.workspace.PushDecision;
import dev.codespire.workspace.PushGate;
import dev.codespire.workspace.Workspace;
import dev.codespire.workspace.WorkspaceSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Workspace → sandbox → harness → gate → push. The only class that touches all four seams. */
@ApplicationScoped
public class RunExecutor {

    private static final Logger LOG = Logger.getLogger(RunExecutor.class);

    @Inject
    RunRuntime runtime;

    @Inject
    HarnessRegistry harnesses;

    public RunResult execute(RunCommand.ExecuteRun command) {
        HarnessAdapter adapter = harnesses.forName(command.harness());
        Path dir;
        try {
            dir = Files.createTempDirectory("spire-run-");
        } catch (Exception e) {
            return new RunResult.RunFailed(command.runId(), "SANDBOX_UNREACHABLE", e.getMessage(), true);
        }

        // Credentials are decrypted here and never leave this method's locals.
        PushCredential push = Credentials.push(command.scmCredential());
        Map<String, String> harnessEnv = Credentials.harnessEnv(command.harnessCredential());

        try (Workspace workspace = Workspace.create(new WorkspaceSpec(
                remoteUri(command), command.baseCommit(), command.branch(), dir, push))) {

            HarnessInvocation invocation = new HarnessInvocation(command.runId(), command.prompt(),
                    "/workspace", command.model(), harnessEnv,
                    Duration.ofSeconds(command.maxWallClockSeconds()),
                    runtime.capabilities().innerSandbox());

            RunSpec spec = new RunSpec(command.runId(), command.agentImage(),
                    adapter.command(invocation), adapter.environment(invocation),
                    workspace.path().toString(), "/workspace",
                    2L * 1024 * 1024 * 1024, 2_000_000_000L,
                    Duration.ofSeconds(command.maxWallClockSeconds()));

            RunHandle handle = runtime.create(spec);
            List<RunEvent> events = new ArrayList<>();
            runtime.attach(handle, line -> adapter.parse(line).ifPresent(events::add));

            Finalization finalization = runtime.finalize(handle);
            if (!finalization.salvaged()) {
                // The workspace is NOT deleted and the container is NOT destroyed: a failed salvage
                // must not throw away the work finalize exists to keep.
                return new RunResult.RunFailed(command.runId(), "SALVAGE_FAILED", finalization.detail(), false);
            }
            runtime.destroy(handle);

            RunEventSummary seen = new RunEventSummary(List.copyOf(events), !events.isEmpty());
            var changes = workspace.changes();
            PushDecision decision = PushGate.decide(changes, command.protectedPaths());

            List<String> changedPaths = changes.paths().stream().map(p -> p.path()).toList();
            if (!decision.allowed()) {
                LOG.warnf("run %s refused at the push gate: %s", command.runId(), decision.blocked());
                return finished(command, null, changedPaths, decision.blocked(), adapter.usage(seen));
            }

            String ref = changes.isEmpty() ? null : workspace.push(push);
            return finished(command, ref, changedPaths, List.of(), adapter.usage(seen));

        } catch (Exception e) {
            return new RunResult.RunFailed(command.runId(), "PROVIDER_ERROR", e.getMessage(), true);
        }
    }

    private RunResult finished(RunCommand.ExecuteRun command, String ref, List<String> changed,
                               List<String> blocked, Optional<UsageReport> usage) {
        // Empty usage means UNKNOWN. It must NOT become zero here — that is the exact conversion
        // ADR-023 exists to prevent, and the ledger prices an UNKNOWN row as unpriceable.
        boolean unknown = usage.isEmpty();
        Long in = unknown ? null : usage.get().tokens(TokenBucket.INPUT);
        Long out = unknown ? null : usage.get().tokens(TokenBucket.OUTPUT);
        return new RunResult.RunFinished(command.runId(), ref, changed, blocked, in, out, unknown);
    }

    private String remoteUri(RunCommand.ExecuteRun command) {
        return command.repo().cloneUrl();
    }
}
```

> If `RepoRef` has no `cloneUrl()`, add one derived from the existing fields rather than
> reconstructing a URL at this call site. Check `spire-contract/.../scm/RepoRef.java` first.

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/** Claim, then ack, then run. The order is the guarantee. */
@ApplicationScoped
public class RunDispatcher {

    private static final Logger LOG = Logger.getLogger(RunDispatcher.class);

    @Inject
    RunClaimStore claims;

    @Inject
    RunExecutor executor;

    @Inject
    RunResultsEmitter results;

    @Incoming("run-commands-in")
    @Blocking
    public void on(RunCommand command) {
        if (command == null) {
            return; // poison record already logged by the deserializer
        }
        MDC.put("runId", command.runId());
        try {
            switch (command) {
                case RunCommand.ExecuteRun execute -> {
                    if (!claims.claim(execute.runId(), "execute")) {
                        LOG.infof("run %s already claimed — redelivery, not a second run", execute.runId());
                        return;
                    }
                    results.emit(executor.execute(execute));
                }
                case RunCommand.CancelRun cancel ->
                        LOG.infof("cancel for %s arrived on the command topic; M1 moves this to "
                                + "cs.run-control, where it does not queue behind the run", cancel.runId());
            }
        } finally {
            MDC.remove("runId");
        }
    }
}
```

Also write, in the same task:

- **`HarnessRegistry`** — a composition root, `@ApplicationScoped`, mapping `"codex"` → `CodexAdapter`, throwing on an unknown name (never falling back to a default: an unknown harness must fail loudly at dispatch, not run the wrong one).
- **`WorkerRuntimes`** — a CDI producer, `@ApplicationScoped`, exposing `DockerRunRuntime` as the `RunRuntime` bean. `spire-runtime-docker` is framework-free, so it carries no CDI annotations of its own and something in the worker must produce it. M0 has one arm; the selection logic arrives with the Kubernetes arm at M5.
- **`RunResultsEmitter`** — an `@Channel("run-results-out")` `Emitter<RunResult>`, keyed by `runId`.
- **`RunCommandDeserializer`** and **`RunResultSerializer`** — copy `ActionCommandDeserializer` / `ActionCommandSerializer` verbatim and change the type. **The never-throw behaviour on a poison record is load-bearing**: a record that cannot be read maps to `null` and is skipped, and processing failures go to `cs.dlq` (ADR-013).
- **`Credentials`** — Tink decrypt via `spire-encryption`, mirroring the review worker's credential unpacking. Its `toString` must not render a secret.

**`HarnessRegistry` and `WorkerRuntimes` are added to the `spire-arch` allowlist in Task 11** — both name an implementation, and that is exactly their job.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :spire-run-worker:test`
Expected: PASS. Quarkus Dev Services boots Postgres; Flyway applies `V1`.

- [ ] **Step 6: Commit**

```bash
git add spire-run-worker settings.gradle.kts build.gradle.kts Dockerfile LICENSING.md
git commit -m "Add the run worker: claim before ack, salvage before teardown"
```

---

## Task 9: `llm_charge` takes a neutral subject

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V40__llm_charge_run_subject.sql`
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
-- not record its capability cannot have one inferred later (ADR-034).
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
- Create: `spire-orchestrator/src/main/resources/db/migration/V41__factory_run.sql`
- Create: `spire-orchestrator/src/main/resources/db/migration/V42__scm_provider_role.sql`
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

The factory must not push as the review bot (ADR-037), and the existing registry cannot hold a second
credential for the same place: `scm_provider` is `UNIQUE (type, workspace)` — verified in
`V3__scm_provider.sql`. So the registry gains a role, and the constraint widens.

```sql
-- ADR-037: the factory pushes as a DEDICATED machine account, not the review bot.
--
-- Two identities, two authority sets. Allowlisting the factory's account as a PR author must not
-- give the review bot allowed-author rights on /review, /finding and /fix — which is what sharing
-- one identity would do, and is the widening ADR-035 forbids.
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
    -- ADR-037: the identity the run pushed as. Recorded, never inferred from an account name,
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
 * defect ADR-036 exists to close.
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
| FR-F2 isolated workspace | 3 |
| FR-F3 sandboxed execution | 6 |
| FR-F4 guaranteed (gated) output | 3, 4, 8 |
| FR-F11 harness registry | 1 (SPI), 8 (`HarnessRegistry`) |
| FR-F13 bring-your-own image | 7, 8 — **the M0 half only** |
| FR-F28 push gate | 4, 8 |
| FR-F29 factory identity | 10 |

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
