package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The launcher driving the real Claude Code adapter, on {@link RunLauncherTest}'s fake runtime and command. */
class RunLauncherClaudeCodeTest {

    private final RunLauncherTest.FakeRuntime runtime = new RunLauncherTest.FakeRuntime();

    /**
     * The launcher's own path: every event of a line through parseAll into the fold, the fold's summary into classify.
     * A Claude Code error result with exit 0 (measured with {@code "subtype":"success"}) fails the run, and its usage
     * still rides on the failure. Reading only the first event of a line, or a fold that drops the result, succeeds.
     */
    @Test
    void anErrorResultLineFailsTheRunThroughTheLauncher() {
        runtime.agentLines = List.of("""
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"TEST-answer"}]},\
                "session_id":"TEST-session"}""", """
                {"type":"result","subtype":"success","is_error":true,"api_error_status":null,\
                "api_error":"connection_failed","usage":{"input_tokens":3,"cache_creation_input_tokens":0,\
                "cache_read_input_tokens":0,"output_tokens":1},"result":"API Error: TEST","session_id":"TEST-session"}""");
        RunLauncher claudeCode = RunLauncherTest.launcher(runtime, new dev.codespire.harness.claudecode.ClaudeCodeAdapter());

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                claudeCode.launch(RunLauncherTest.commandWithNoCredentials(), RunObserver.IGNORING));

        assertEquals("PROVIDER_ERROR", failed.cause());
        assertEquals(3L, failed.tokenUsage().get("INPUT"));
    }
}
