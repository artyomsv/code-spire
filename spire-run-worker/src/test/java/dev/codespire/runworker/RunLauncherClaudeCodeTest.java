package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PullRefs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.claudecode.ClaudeCodeAdapter;
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

    /** A runtime that counts the units it is asked to create. */
    private static RunLauncherTest.FakeRuntime countingRuntime(int[] created) {
        return new RunLauncherTest.FakeRuntime() {
            @Override
            public dev.codespire.runtime.RunHandle create(dev.codespire.runtime.RunUnitSpec spec) {
                created[0]++;
                return super.create(spec);
            }
        };
    }

    /** A fork's review on Claude Code, its credentials sealed under the given encryption. */
    private static RunCommand.ExecuteUntrustedReviewRun forkOnClaudeCode(EncryptionService encryption)
            throws Exception {
        String runId = "run::github:TEST-work/app:review-7-aaaaaaaaaaaa-0123abcd:1";
        String scm = encryption.encryptString(new ObjectMapper().writeValueAsString(
                new MachineAccountCredential("TEST-bot", "TEST-scm-token")), RunCommand.scmCredentialAad(runId));
        String harness = encryption.encryptString("TEST-harness-key", RunCommand.harnessCredentialAad(runId));
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(runId, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "main", "a".repeat(40), PullRefs.localBranch(7),
                "TEST-review", "claude-code", "TEST-model", "TEST-image", List.of(), 60, scm, harness, false, "main");
        ReviewRunBinding binding = new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7,
                "a".repeat(40), "TEST-slot", 1);
        return new RunCommand.ExecuteUntrustedReviewRun(runId, execution, binding, HarnessReviewInputs.NONE,
                "refs/pull/7/head");
    }

    /**
     * A fork's review on Claude Code with the credential proxy off (the builder's default): the real builder and the
     * real adapter refuse it as a fork, not as the adapter's credential_proxy_required, and no container is created.
     */
    @Test
    void anUnproxiedForkFailsBadCommandAsAFork() throws Exception {
        int[] created = {0};
        EncryptionService encryption = new EncryptionService(EncryptionService.generateKeysetBase64());
        RunLauncher launcher = RunLauncherTest.launcher(countingRuntime(created), new ClaudeCodeAdapter());
        launcher.builder = RunUnitBuilderTest.builder(encryption);

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher.launchUntrustedReview(forkOnClaudeCode(encryption), RunObserver.IGNORING));

        assertEquals("BAD_COMMAND", failed.cause());
        assertTrue(failed.detail().contains("fork_requires_credential_proxy"), failed.detail());
        assertFalse(failed.detail().contains("credential_proxy_required"), failed.detail());
        assertEquals(0, created[0], "no unit is created for an unproxied fork");
    }
}
