package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.UsageReport;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the launcher turns two log streams and an exit code into ONE result, with the runtime faked.
 *
 * <p>A review's mutation found that "report every failed agent run as RunFinished" passed the whole
 * module: nothing below the Docker integration tests exercised {@code interpret}. These are the
 * cases that decide what an operator is told.
 */
class RunLauncherTest {

    private static final RunCommand.ExecuteRun COMMAND = new RunCommand.ExecuteRun(
            "run::github:acme/app:finding-1:1", new RepoRef("acme", "app"),
            "https://github.com/acme/app.git", "main", "abc1234", "spire/finding-1",
            "fix the typo", "codex", "gpt-5.6", "img", List.of(), 60, "enc-scm", "enc-harness");

    /** Exit 0 succeeds; anything else is a harness failure. Nothing parsed, usage unknown. */
    static final class FakeAdapter implements HarnessAdapter {
        @Override
        public HarnessType type() {
            return HarnessType.CODEX;
        }

        @Override
        public HarnessCapabilities capabilities() {
            return null;
        }

        @Override
        public PromptDelivery promptDelivery() {
            return PromptDelivery.STDIN;
        }

        @Override
        public List<String> command(HarnessInvocation invocation) {
            return List.of("fake");
        }

        @Override
        public Map<String, String> environment(HarnessInvocation invocation) {
            return Map.of();
        }

        @Override
        public Optional<RunEvent> parse(String line) {
            return line.startsWith("out:") ? Optional.of(new RunEvent.Output(Instant.EPOCH, line)) : Optional.empty();
        }

        @Override
        public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
            return exitCode == 0 ? TerminalOutcome.success("ok")
                    : TerminalOutcome.failure(FailureCause.HARNESS_EXIT_NONZERO, "exit " + exitCode);
        }

        @Override
        public UsageReport usage(RunEventSummary seen) {
            return UsageReport.unknown();
        }
    }

    /** Replays canned lines on each channel, reports a canned exit, and records what was destroyed. */
    static final class FakeRuntime implements RunRuntime {
        List<String> agentLines = List.of();
        List<String> publisherLines = List.of();
        Finalization finalization = Finalization.salvaged(0, "exited");
        RuntimeException createFails;
        RuntimeException salvageFails;
        /** A real follow stream: the reader blocks until it is interrupted. */
        boolean readersBlock;
        final java.util.concurrent.CountDownLatch readersInterrupted = new java.util.concurrent.CountDownLatch(2);
        RuntimeException agentReaderFails;
        final List<RunHandle> destroyed = new ArrayList<>();
        /** What this arm claims salvage may hold the handler for; the ack budget reads it. */
        Duration drainWindow = Duration.ZERO;

        @Override
        public RuntimeType type() {
            return null;
        }

        @Override
        public RuntimeCapabilities capabilities() {
            return null;
        }

        @Override
        public RunHandle create(RunUnitSpec spec) {
            if (createFails != null) {
                throw createFails;
            }
            return new RunHandle(COMMAND.runId(), "unit-1");
        }

        @Override
        public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
            (channel == LogChannel.AGENT ? agentLines : publisherLines).forEach(lines);
            if (agentReaderFails != null && channel == LogChannel.AGENT) {
                throw agentReaderFails;
            }
            if (readersBlock) {
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    readersInterrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void cancel(RunHandle handle) {
        }

        @Override
        public Finalization salvage(RunHandle handle) {
            if (salvageFails != null) {
                throw salvageFails;
            }
            return finalization;
        }

        @Override
        public void destroy(RunHandle handle) {
            destroyed.add(handle);
        }

        @Override
        public List<RunHandle> discoverOrphans() {
            return List.of();
        }

        @Override
        public Duration drainWindow() {
            return drainWindow;
        }
    }

    private final FakeRuntime runtime = new FakeRuntime();
    private final RunLauncher launcher = launcher(runtime);

    private static RunLauncher launcher(FakeRuntime runtime) {
        RunLauncher launcher = new RunLauncher();
        launcher.runtime = runtime;
        launcher.harnesses = new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                return new FakeAdapter();
            }
        };
        launcher.builder = new RunUnitBuilder() {
            @Override
            public RunUnitSpec build(RunCommand.ExecuteRun command, HarnessAdapter adapter) {
                return null; // the fake runtime never reads it
            }
        };
        return launcher;
    }

    @Test
    void aPushedRunIsFinishedWithItsRef() {
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[{\"path\":\"a.txt\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertEquals(List.of("a.txt"), finished.changedPaths());
        assertNull(finished.tokenUsage(), "unknown usage is null, never an empty map");
        assertEquals(1, runtime.destroyed.size(), "a salvaged unit is destroyed");
    }

    @Test
    void aFailedAgentThatPushedNothingIsFailedNotFinished() {
        runtime.finalization = Finalization.salvaged(2, "exited");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));

        // The harness's own word rides the wire unchanged; the closed set answers what it means.
        assertEquals("HARNESS_EXIT_NONZERO", failed.cause());
        assertEquals(RunFailureCause.AGENT_FAILED, RunFailureCause.of(failed.cause()));

        // Was reported retryable. An agent that ran to completion and failed will fail the same
        // prompt against the same commit again, and the model has already been paid for — so the
        // retry bought nothing and cost a second full run. A provider outage is the case that
        // deserves the retry, and it is MODEL_UNAVAILABLE, which is a different value for exactly
        // this reason.
        assertFalse(failed.retryable());
    }

    @Test
    void aFailedAgentThatHadAlreadyPushedIsStillFinished() {
        // The work is on the branch either way; a "failed" here would send the operator to look
        // for a lost commit that is sitting on the remote.
        runtime.finalization = Finalization.salvaged(2, "exited");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
    }

    @Test
    void aFailedSalvageAfterAPushNamesTheRefAndPreservesTheUnit() {
        // An hour of pushed checkpoints, then a wall-clock overrun: the failure must say where the
        // work is, or the operator hunts for a lost commit that is sitting on the remote.
        runtime.finalization = Finalization.salvageFailed("agent did not exit within the run's wall clock");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));

        assertEquals("SALVAGE_FAILED", failed.cause());
        assertTrue(failed.detail().contains("refs/heads/spire/finding-1"), failed.detail());
        assertTrue(runtime.destroyed.isEmpty(), "a unit whose salvage failed is preserved for inspection");
    }

    @Test
    void aThrowingSalvageStillReportsTheRunAndWhatWasPushed() {
        // Before this, a daemon fault inside salvage propagated out of launch: no terminal result,
        // the sibling log reader blocked for the life of the process, and the unit leaked unnamed.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));

        assertEquals("SALVAGE_FAILED", failed.cause());
        assertTrue(failed.detail().contains("daemon went away"), failed.detail());
        assertTrue(failed.detail().contains("refs/heads/spire/finding-1"), failed.detail());
    }

    @Test
    void aThrowingSalvageInterruptsReadersThatAreStillFollowing() throws Exception {
        // CompletableFuture.cancel(true) "has no effect": the readers it cancelled kept blocking on
        // the follow stream of a preserved unit for the life of the process — a virtual thread and
        // a daemon connection each. An ExecutorService Future's cancel(true) interrupts.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.readersBlock = true;

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));

        assertEquals("SALVAGE_FAILED", failed.cause());
        assertTrue(runtime.readersInterrupted.await(15, java.util.concurrent.TimeUnit.SECONDS),
                "both readers must be interrupted, not left following a stream that never ends");
    }

    @Test
    void aReaderFaultAfterAGoodSalvageIsALoggingFaultNotARunFailure() {
        // The exit code and what was read still decide the result, and the unit is destroyed:
        // a completed run must not be recorded as an infrastructure failure and leaked.
        runtime.agentReaderFails = new IllegalStateException("log stream reset");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND));

        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertEquals(1, runtime.destroyed.size(), "a salvaged unit is destroyed, reader fault or not");
    }

    @Test
    void aFailureIsRetryableOnlyWhenItsCauseIs() {
        // Every publisher failure used to be reported retryable. A push the forge rejected refuses
        // the same way next time, so the retry bought nothing and cost another whole agent run --
        // the expensive half, since the model has already been paid for by the time we get here.
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"rejected\"}");
        RunResult.RunFailed rejected = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals(RunFailureCause.PUSH_REJECTED, RunFailureCause.of(rejected.cause()));
        assertFalse(rejected.retryable(), "a rejected push is an answer, not weather");

        // ...while a clone that could not reach the forge genuinely might succeed on a retry.
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"CLONE_FAILED\",\"detail\":\"connection reset\"}");
        RunResult.RunFailed clone = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals(RunFailureCause.CLONE_FAILED, RunFailureCause.of(clone.cause()));
        assertTrue(clone.retryable(), "a transport failure reaching the forge is worth one retry");
    }

    @Test
    void aBundleThePublisherCouldNotReadAfterAGoodPushLeavesThePushStanding() {
        // BUNDLE_UNREADABLE is not terminal: the publisher skips that bundle and reads on. Four
        // checkpoints really are on the branch, and the result must say so.
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[{\"path\":\"a.txt\",\"kind\":\"ADDED\"}]}",
                "{\"event\":\"failed\",\"cause\":\"BUNDLE_UNREADABLE\",\"detail\":\"BundleTooLargeException: 300MB\"}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
    }

    @Test
    void aPublisherFailureOutranksACleanAgentExit() {
        runtime.publisherLines = List.of("{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"rejected\"}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals("PUSH_FAILED", failed.cause());
    }

    @Test
    void aGateRefusalIsFinishedAsRefusedNotFailed() {
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}],"
                        + "\"changed\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND));
        assertTrue(finished.refused());
        assertEquals(List.of(".github/workflows/ci.yml"), finished.blockedPaths());
        assertNull(finished.pushedRef());
    }

    @Test
    void aFailedSalvagePreservesTheUnitAndSaysSo() {
        runtime.finalization = Finalization.salvageFailed("daemon hung up mid-wait");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals("SALVAGE_FAILED", failed.cause());
        assertFalse(failed.retryable());
        assertTrue(runtime.destroyed.isEmpty(), "an unsalvaged unit is kept for inspection, never destroyed");
    }

    @Test
    void anUnreachableSandboxIsRetryable() {
        runtime.createFails = new IllegalStateException("no daemon");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals("SANDBOX_UNREACHABLE", failed.cause());
        assertTrue(failed.retryable());
    }

    @Test
    void aBadCommandFailsBeforeAnythingIsCreatedAndIsNotRetried() {
        launcher.harnesses = new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                throw new IllegalArgumentException("unknown harness: " + harness);
            }
        };

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND));
        assertEquals("BAD_COMMAND", failed.cause());
        assertFalse(failed.retryable());
        assertTrue(runtime.destroyed.isEmpty());
    }
}
