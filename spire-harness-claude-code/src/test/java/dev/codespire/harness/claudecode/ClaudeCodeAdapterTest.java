package dev.codespire.harness.claudecode;

import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Claude Code arm: its command, its refusals and its reading of the stream-json lines (Task 3's record). */
class ClaudeCodeAdapterTest {

    private final ClaudeCodeAdapter adapter = new ClaudeCodeAdapter();

    @Test
    void theArmDeclaresWhatWasMeasured() {
        assertEquals(HarnessType.CLAUDE_CODE, adapter.type());
        assertEquals(new HarnessCapabilities(true, true, false, false, false), adapter.capabilities());
        assertEquals(PromptDelivery.STDIN, adapter.promptDelivery());
        assertEquals(Optional.empty(), adapter.signIn());
        assertEquals(Set.of("api.anthropic.com"), adapter.egressHosts(ClaudeCodeInvocations.review()));
    }

    /** The flags measured in Task 3, the guarded set on every run, and the model; the prompt arrives on stdin. */
    @Test
    void theCommandRunsTheMeasuredFlags() {
        List<String> argv = adapter.command(ClaudeCodeInvocations.review());

        assertEquals(List.of("-p", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions"),
                ClaudeCodeAdapter.RUN_FLAGS);
        assertEquals(List.of("--bare", "--safe-mode", "--strict-mcp-config", "--disable-slash-commands",
                "--setting-sources", "user"), ClaudeCodeAdapter.GUARDED_FLAGS);
        assertEquals(List.of("sh", "-c", "cd '/workspace' && exec claude -p --output-format stream-json --verbose"
                + " --dangerously-skip-permissions --bare --safe-mode --strict-mcp-config --disable-slash-commands"
                + " --setting-sources user --model 'claude-haiku-5-5'"), argv);
        assertFalse(String.join(" ", argv).contains(ClaudeCodeInvocations.PROMPT), argv.toString());
    }

    /**
     * {@code --bare} is what stops CLAUDE.md being found, so there is no separate memory switch: the guarded flags are on
     * every invocation the adapter accepts, whatever its credential map holds.
     */
    @Test
    void aReviewRunLoadsNoProjectMemory() {
        List<Map<String, String>> credentialMaps = List.of(Map.of(),
                Map.of(HarnessInvocation.CREDENTIAL, ClaudeCodeModelProxy.PLACEHOLDER),
                Map.of("TEST_EXTRA", "TEST-value"));
        for (Map<String, String> credentials : credentialMaps) {
            String script = adapter.command(ClaudeCodeInvocations.review(credentials)).get(2);
            assertTrue(script.contains(" " + String.join(" ", ClaudeCodeAdapter.GUARDED_FLAGS) + " "), script);
        }
    }

    /** Defence only: the catalogue lists no thinking level for Claude Code, so HarnessCatalogues.admit refuses first. */
    @Test
    void aReasoningEffortIsRefused() {
        HarnessInvocation withEffort = new HarnessInvocation("run_TEST", "TEST-review", "/workspace",
                ClaudeCodeInvocations.MODEL, Map.of(), Duration.ofMinutes(30), "high")
                .proxied().withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> adapter.command(withEffort));
        assertEquals("effort_not_offered", refused.getMessage());
    }

    @Test
    void aModelThatCouldCloseTheQuoteIsRefused() {
        HarnessInvocation quoted = new HarnessInvocation("run_TEST", "TEST-review", "/workspace", "TEST'model",
                Map.of(), Duration.ofMinutes(30)).proxied()
                .withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED);

        assertThrows(IllegalArgumentException.class, () -> adapter.command(quoted));
    }

    @Test
    void aRunThatIsNotProxiedIsRefused() {
        HarnessInvocation direct = ClaudeCodeInvocations.direct(Map.of(HarnessInvocation.CREDENTIAL, "TEST-key"))
                .withProjectGuidance(HarnessInvocation.ProjectGuidance.IGNORED);

        assertEquals("credential_proxy_required",
                assertThrows(IllegalStateException.class, () -> adapter.command(direct)).getMessage());
        assertEquals("credential_proxy_required",
                assertThrows(IllegalStateException.class, () -> adapter.environment(direct)).getMessage());
    }

    @Test
    void aRunThatLoadsGuidanceIsRefused() {
        HarnessInvocation loaded = ClaudeCodeInvocations.review()
                .withProjectGuidance(HarnessInvocation.ProjectGuidance.LOADED);

        assertEquals("harness_reviews_only",
                assertThrows(IllegalStateException.class, () -> adapter.command(loaded)).getMessage());
        assertEquals("harness_reviews_only",
                assertThrows(IllegalStateException.class, () -> adapter.environment(loaded)).getMessage());
    }

    // ---- lines ------------------------------------------------------------------------------

    @Test
    void oneAssistantLineYieldsOneEventPerBlock() {
        List<RunEvent> events = adapter.parseAll(ClaudeCodeLines.THREE_BLOCKS);

        assertEquals(3, events.size(), events.toString());
        assertEquals("TEST-answer", assertInstanceOf(RunEvent.Output.class, events.get(0)).text());
        assertEquals("TEST-thought", assertInstanceOf(RunEvent.Thinking.class, events.get(1)).text());
        RunEvent.ToolUse tool = assertInstanceOf(RunEvent.ToolUse.class, events.get(2));
        assertEquals("Bash", tool.tool());
        assertTrue(tool.summary().contains("git diff TEST-base HEAD"), tool.summary());
        // Each event is stamped when it is read, so the two reads compare by content, not by record equality.
        RunEvent.Output first = assertInstanceOf(RunEvent.Output.class,
                adapter.parse(ClaudeCodeLines.THREE_BLOCKS).orElseThrow(), "parse is the first event of parseAll");
        assertEquals("TEST-answer", first.text());
    }

    /** An assistant message's own usage is per call; the run's usage is the result line's, so this line reports none. */
    @Test
    void anAssistantLineReportsNoUsage() {
        List<RunEvent> events = adapter.parseAll(ClaudeCodeLines.TEXT);

        assertEquals(1, events.size(), events.toString());
        assertInstanceOf(RunEvent.Output.class, events.get(0));
    }

    @Test
    void aToolResultNamesItsToolUseId() {
        List<RunEvent> events = adapter.parseAll(ClaudeCodeLines.TOOL_RESULT);

        assertEquals(1, events.size(), events.toString());
        RunEvent.ToolResult result = assertInstanceOf(RunEvent.ToolResult.class, events.get(0));
        assertEquals("toolu_TEST-1", result.tool());
        assertFalse(result.error());
        assertEquals("TEST-diff", result.summary());
    }

    @Test
    void theResultEventCarriesUsage() {
        List<RunEvent> events = adapter.parseAll(ClaudeCodeLines.RESULT_SUCCESS);

        assertEquals(2, events.size(), events.toString());
        UsageReport report = assertInstanceOf(RunEvent.Usage.class, events.get(0)).report();
        assertEquals(Map.of(TokenBucket.INPUT, 10L, TokenBucket.CACHED_INPUT, 20L, TokenBucket.CACHE_WRITE, 30L,
                TokenBucket.OUTPUT, 40L), report.asMap().orElseThrow());
        RunEvent.StateChange state = assertInstanceOf(RunEvent.StateChange.class, events.get(1));
        assertEquals("result", state.state());
        assertEquals("success", state.detail());
        assertEquals(report, adapter.usage(RunEventSummary.of(events)));
    }

    /**
     * Measured: the error result said {@code "subtype":"success"} with no API status; {@code is_error} decides, and
     * the detail names the first of {@code api_error_status}, {@code api_error}, {@code terminal_reason} and
     * {@code subtype} that says something, never {@code error:success}.
     */
    @Test
    void anErrorResultIsAnErrorWhateverItsSubtype() {
        List<RunEvent> events = adapter.parseAll(ClaudeCodeLines.RESULT_ERROR);

        RunEvent.StateChange state = assertInstanceOf(RunEvent.StateChange.class, events.get(1));
        assertEquals("result", state.state());
        assertEquals("error:connection_failed", state.detail());
    }

    @Test
    void anErrorResultWithNoApiErrorNamesItsTerminalReason() {
        String line = ClaudeCodeLines.RESULT_ERROR.replace("\"api_error\":\"connection_failed\"", "\"api_error\":\" \"");

        RunEvent.StateChange state = assertInstanceOf(RunEvent.StateChange.class, adapter.parseAll(line).get(1));
        assertEquals("error:api_error", state.detail());
    }

    @Test
    void anErrorResultNamesItsApiStatus() {
        String line = ClaudeCodeLines.RESULT_ERROR.replace("\"api_error_status\":null", "\"api_error_status\":401");

        RunEvent.StateChange state = assertInstanceOf(RunEvent.StateChange.class, adapter.parseAll(line).get(1));
        assertEquals("error:401", state.detail());
    }

    @Test
    void aResultWithNoUsageReportsNone() {
        String line = """
                {"type":"result","subtype":"success","is_error":false,"result":"TEST-answer"}""";
        List<RunEvent> events = adapter.parseAll(line);

        assertEquals(1, events.size(), events.toString());
        assertInstanceOf(RunEvent.StateChange.class, events.get(0));
        assertTrue(adapter.usage(RunEventSummary.of(events)).isUnknown());
    }

    /** A count that is missing reads as zero only beside counts that are present; a count that is not a number, none. */
    @Test
    void aMissingCountIsZeroOnlyBesideOthers() {
        String partial = """
                {"type":"result","subtype":"success","is_error":false,"usage":{"input_tokens":7,"output_tokens":9}}""";
        String garbled = """
                {"type":"result","subtype":"success","is_error":false,"usage":{"input_tokens":"TEST","output_tokens":9}}""";
        String empty = """
                {"type":"result","subtype":"success","is_error":false,"usage":{"service_tier":"standard"}}""";

        UsageReport report = assertInstanceOf(RunEvent.Usage.class, adapter.parseAll(partial).get(0)).report();
        assertEquals(Map.of(TokenBucket.INPUT, 7L, TokenBucket.CACHED_INPUT, 0L, TokenBucket.CACHE_WRITE, 0L,
                TokenBucket.OUTPUT, 9L), report.asMap().orElseThrow());
        assertEquals(1, adapter.parseAll(garbled).size(), "no usage, only the state");
        assertEquals(1, adapter.parseAll(empty).size(), "no usage, only the state");
    }

    @Test
    void otherSystemLinesYieldNothing() {
        assertEquals(List.of(), adapter.parseAll(ClaudeCodeLines.SYSTEM_INIT));
        assertEquals(List.of(), adapter.parseAll(ClaudeCodeLines.API_RETRY));
    }

    @Test
    void aNonJsonLineYieldsNothing() {
        assertEquals(List.of(), adapter.parseAll("not json"));
        assertEquals(List.of(), adapter.parseAll(""));
        assertEquals(List.of(), adapter.parseAll(null));
        assertEquals(List.of(), adapter.parseAll("[1,2,3]"));
        assertTrue(adapter.parse("not json").isEmpty());
    }

    @Test
    void aLongTextIsClipped() {
        String line = ClaudeCodeLines.TEXT.replace("TEST-answer", "z".repeat(50_000));

        String text = assertInstanceOf(RunEvent.Output.class, adapter.parseAll(line).get(0)).text();
        assertTrue(text.length() < 2_100, "length " + text.length());
    }

    // ---- outcome ----------------------------------------------------------------------------

    @Test
    void noResultEventIsNoModelResponse() {
        TerminalOutcome outcome = adapter.classify(0, RunEventSummary.of(List.of()));

        assertFalse(outcome.succeeded());
        assertEquals(Optional.of(FailureCause.NO_MODEL_RESPONSE), outcome.cause());
    }

    @Test
    void aSuccessResultWithAFailedExitIsAFailedExit() {
        List<RunEvent> events = new ArrayList<>(adapter.parseAll(ClaudeCodeLines.TEXT));
        events.addAll(adapter.parseAll(ClaudeCodeLines.RESULT_SUCCESS));

        TerminalOutcome outcome = adapter.classify(1, RunEventSummary.of(events));
        assertEquals(Optional.of(FailureCause.HARNESS_EXIT_NONZERO), outcome.cause());
    }

    @Test
    void anErrorResultFailsEvenOnExitZero() {
        RunEventSummary seen = RunEventSummary.of(adapter.parseAll(ClaudeCodeLines.RESULT_ERROR));

        assertEquals(Optional.of(FailureCause.PROVIDER_ERROR), adapter.classify(0, seen).cause());
    }

    @Test
    void theLastUsageWins() {
        List<RunEvent> events = new ArrayList<>(adapter.parseAll(ClaudeCodeLines.RESULT_ERROR));
        events.addAll(adapter.parseAll(ClaudeCodeLines.RESULT_SUCCESS));

        assertEquals(10L, adapter.usage(RunEventSummary.of(events)).tokens(TokenBucket.INPUT));
    }
}
