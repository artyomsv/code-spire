package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A fork's review on Claude Code with the credential proxy off (the builder's default): the real builder and the
     * real adapter refuse it as a fork, not as the adapter's credential_proxy_required, and no container is created.
     */
    @Test
    void anUnproxiedForkFailsBadCommandAsAFork() throws Exception {
        int[] created = {0};
        RunLauncherTest.FakeRuntime counting = new RunLauncherTest.FakeRuntime() {
            @Override
            public dev.codespire.runtime.RunHandle create(dev.codespire.runtime.RunUnitSpec spec) {
                created[0]++;
                return super.create(spec);
            }
        };
        dev.codespire.encryption.EncryptionService encryption = new dev.codespire.encryption.EncryptionService(
                dev.codespire.encryption.EncryptionService.generateKeysetBase64());
        RunLauncher launcher = RunLauncherTest.launcher(counting, new dev.codespire.harness.claudecode.ClaudeCodeAdapter());
        launcher.builder = RunUnitBuilderTest.builder(encryption);
        String runId = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";
        String scm = encryption.encryptString(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new dev.codespire.contract.command.MachineAccountCredential("TEST-bot", "TEST-scm-token")),
                dev.codespire.contract.command.RunCommand.scmCredentialAad(runId));
        String harness = encryption.encryptString("TEST-harness-key",
                dev.codespire.contract.command.RunCommand.harnessCredentialAad(runId));
        dev.codespire.contract.command.RunCommand.ExecuteRun execution =
                new dev.codespire.contract.command.RunCommand.ExecuteRun(runId,
                        new dev.codespire.contract.scm.RepoRef("TEST-work", "app"),
                        "https://forge.example.test/TEST-work/app.git", "main", "a".repeat(40),
                        dev.codespire.contract.review.PullRefs.localBranch(7), "TEST-review", "claude-code",
                        "TEST-model", "TEST-image", List.of(), 60, scm, harness, false, "main");
        dev.codespire.contract.command.RunCommand.ExecuteUntrustedReviewRun fork =
                new dev.codespire.contract.command.RunCommand.ExecuteUntrustedReviewRun(runId, execution,
                        new dev.codespire.contract.review.ReviewRunBinding("TEST-review",
                                dev.codespire.contract.review.ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40),
                                "TEST-slot", 1),
                        dev.codespire.contract.review.HarnessReviewInputs.NONE, "refs/pull/7/head");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher.launchUntrustedReview(fork, RunObserver.IGNORING));

        assertEquals("BAD_COMMAND", failed.cause());
        assertTrue(failed.detail().contains("fork_requires_credential_proxy"), failed.detail());
        assertFalse(failed.detail().contains("credential_proxy_required"), failed.detail());
        assertEquals(0, created[0], "no unit is created for an unproxied fork");
    }
}
