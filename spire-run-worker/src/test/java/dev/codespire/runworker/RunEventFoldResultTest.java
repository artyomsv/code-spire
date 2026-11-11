package dev.codespire.runworker;

import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;
import dev.codespire.harness.claudecode.ClaudeCodeAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real path a run's outcome takes: each agent line through {@link HarnessAdapter#parseAll}, every event into the
 * fold, and the fold's summary into {@code classify} and {@code usage}. A result the adapter reads correctly but the
 * fold drops would let an error result with exit 0 succeed. The lines have the shapes Claude Code 2.1.319 emitted in
 * the 2026-11-11 measurement, with TEST- values.
 */
class RunEventFoldResultTest {

    private static final String TEXT = """
            {"type":"assistant","message":{"model":"claude-haiku-5-5","id":"msg_TEST-1","type":"message",\
            "role":"assistant","content":[{"type":"text","text":"TEST-answer"}],"stop_reason":null},\
            "parent_tool_use_id":null,"session_id":"TEST-session","uuid":"TEST-uuid-1"}""";

    private static final String RESULT_SUCCESS = """
            {"type":"result","subtype":"success","is_error":false,"num_turns":3,"api_error_status":null,\
            "usage":{"input_tokens":10,"cache_creation_input_tokens":30,"cache_read_input_tokens":20,\
            "output_tokens":40},"result":"TEST-answer","session_id":"TEST-session","uuid":"TEST-uuid-2"}""";

    /** Measured: an error result says {@code "subtype":"success"} and {@code "is_error":true}. */
    private static final String RESULT_ERROR = """
            {"type":"result","subtype":"success","is_error":true,"num_turns":1,"api_error_status":null,\
            "api_error":"connection_failed","terminal_reason":"api_error","usage":{"input_tokens":0,\
            "cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},\
            "result":"API Error: TEST-unreachable","session_id":"TEST-session","uuid":"TEST-uuid-3"}""";

    private final HarnessAdapter adapter = new ClaudeCodeAdapter();

    private RunEventSummary fold(String... lines) {
        RunEventFold fold = new RunEventFold();
        for (String line : lines) {
            adapter.parseAll(line).forEach(fold::accept);
        }
        return fold.summary();
    }

    @Test
    void anErrorResultFailsThroughTheFold() {
        RunEventSummary seen = fold(TEXT, RESULT_ERROR);

        TerminalOutcome outcome = adapter.classify(0, seen);
        assertFalse(outcome.succeeded());
        assertEquals(Optional.of(FailureCause.PROVIDER_ERROR), outcome.cause());
        UsageReport usage = adapter.usage(seen);
        assertFalse(usage.isUnknown(), "the error result's usage is still reported");
        assertEquals(0L, usage.tokens(TokenBucket.INPUT));
    }

    @Test
    void aSuccessResultSucceedsThroughTheFold() {
        RunEventSummary seen = fold(TEXT, RESULT_SUCCESS);

        assertTrue(adapter.classify(0, seen).succeeded());
        assertEquals(10L, adapter.usage(seen).tokens(TokenBucket.INPUT));
    }

    /** Only the last result is carried: every other state change is counted and dropped, as before. */
    @Test
    void theFoldKeepsOnlyTheLastResult() {
        RunEventFold fold = new RunEventFold();
        RunEvent.StateChange first = new RunEvent.StateChange(Instant.EPOCH, "result", "error:TEST");
        RunEvent.StateChange last = new RunEvent.StateChange(Instant.EPOCH, "result", "success");
        fold.accept(first);
        fold.accept(new RunEvent.StateChange(Instant.EPOCH, "TEST-other", ""));
        fold.accept(last);

        List<RunEvent> events = fold.summary().events();
        assertEquals(List.of(last), events);
        assertEquals(2, fold.dropped());
    }
}
