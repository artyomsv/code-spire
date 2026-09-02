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
import dev.codespire.harness.TokenBucket;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    static final String SCM_USERNAME = "TEST-machine-account";

    static final String READ_SECRET = "TEST-read-secret-0123456789";

    static final String WRITE_SECRET = "TEST-write-secret-abcdefgh";

    /** The credential the deleted debt entry named as its risk, and the one the first scrub omitted. */
    static final String MODEL_KEY = "TEST-model-key-9876543210";

    /** Exit 0 succeeds; anything else is a harness failure. Usage is whatever the test sets. */
    static final class FakeAdapter implements HarnessAdapter {

        /** What this harness reports having spent. Unknown unless a test says otherwise. */
        UsageReport usage = UsageReport.unknown();

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
            return usage;
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
        /** Every lifecycle call in order, so "salvage before destroy" is a fact rather than a hope. */
        final List<String> lifecycle = new ArrayList<>();
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

        /** Recorded, so stopping a preserved unit is asserted rather than assumed. */
        final List<RunHandle> cancelled = new java.util.ArrayList<>();

        RuntimeException cancelFails;

        @Override
        public void cancel(RunHandle handle) {
            lifecycle.add("cancel");
            cancelled.add(handle);
            if (cancelFails != null) {
                throw cancelFails;
            }
        }

        @Override
        public void steer(RunHandle handle, String instruction) {
            throw new UnsupportedOperationException("no shipped harness declares steering");
        }

        @Override
        public Finalization salvage(RunHandle handle) {
            lifecycle.add("salvage");
            if (salvageFails != null) {
                throw salvageFails;
            }
            return finalization;
        }

        RuntimeException destroyFails;

        @Override
        public void destroy(RunHandle handle) {
            lifecycle.add("destroy");
            destroyed.add(handle);
            if (destroyFails != null) {
                throw destroyFails;
            }
        }

        @Override
        public List<RunHandle> discoverUnits() {
            return List.of();
        }

        @Override
        public Duration drainWindow() {
            return drainWindow;
        }
    }

    private final FakeRuntime runtime = new FakeRuntime();
    /** One instance, so a test can say what the harness reported and see whether it survives. */
    private final FakeAdapter adapter = new FakeAdapter();

    private final RunLauncher launcher = launcher(runtime, adapter);

    /** The real collaborator, with only its credential source faked. */
    static RunFailures failuresWith(Credentials credentials) {
        RunFailures failures = new RunFailures();
        failures.credentials = credentials;
        return failures;
    }

    private static RunLauncher launcher(FakeRuntime runtime, FakeAdapter adapter) {
        RunLauncher launcher = new RunLauncher();
        launcher.runtime = runtime;
        launcher.harnesses = new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                return adapter;
            }
        };
        launcher.failures = failuresWith(new Credentials() {
            @Override
            public Scm scm(String runId, String packed) {
                return new Scm(SCM_USERNAME, READ_SECRET, SCM_USERNAME, WRITE_SECRET);
            }

            @Override
            public Map<String, String> harnessEnv(String runId, String packed) {
                return Map.of("OPENAI_API_KEY", MODEL_KEY);
            }
        });
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

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertEquals(List.of("a.txt"), finished.changedPaths());
        assertNull(finished.tokenUsage(), "unknown usage is null, never an empty map");
        assertEquals(1, runtime.destroyed.size(), "a salvaged unit is destroyed");
    }

    @Test
    void aFailedAgentThatPushedNothingIsFailedNotFinished() {
        runtime.finalization = Finalization.salvaged(2, "exited");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

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

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
    }

    @Test
    void aRunThatPushedBeforeOverrunningIsFinishedWithItsRef() {
        // An hour of pushed checkpoints, then a wall-clock overrun. The branch really holds that
        // work, so a result saying SALVAGE_FAILED with no ref tells the operator nothing was
        // delivered and sends them hunting for commits that are sitting on the remote.
        //
        // It also contradicted the rule interpret() states a few lines further down for the agent's
        // own failure: a run that pushed before failing is finished, because the work is on the
        // branch either way. The overrun path did not follow its own rule.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[{\"path\":\"a.txt\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals("refs/heads/spire/finding-1", finished.pushedRef(),
                "the run record must know about commits that exist on the remote");
        assertEquals(List.of("a.txt"), finished.changedPaths());
        assertTrue(runtime.destroyed.isEmpty(),
                "and the unit is still preserved: nothing observed its exit, so there is still "
                        + "something an operator may need to read");
        assertTrue(finished.agentUnobserved(),
                "the work is on the branch AND the run did not finish — the result must carry both, "
                        + "because reporting only the push asserts a clean delivery for an agent "
                        + "that was killed mid-thought");
        assertTrue(finished.deliveredUnfinished());
        assertEquals(1, runtime.cancelled.size(),
                "a preserved unit is STOPPED: that an overrun kills the agent is one arm's private "
                        + "promise, and a result now saying the run finished makes an operator rely on it");
    }

    @Test
    void aRunThatDeliveredAndDidFinishSaysSo() {
        // The other half of the flag, without which "unobserved" could be hardcoded true and every
        // test above would still pass while every clean run was labelled unfinished.
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertFalse(finished.agentUnobserved());
        assertFalse(finished.deliveredUnfinished());
        assertTrue(runtime.cancelled.isEmpty(), "an observed run is destroyed, not cancelled");
    }

    @Test
    void aGateRefusalOutranksTheClockThatRanOutAroundIt() {
        // The first version of the unobserved branch looked only for a push, so a refusal fell
        // through to AGENT_TIMEOUT with its blocked paths discarded — and RUN_PUSH_GATE_REFUSED,
        // which keys on the refused status, could never fire for a long run.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}],"
                        + "\"changed\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertTrue(finished.refused(), "the gate's refusal is the run's outcome, clock or no clock");
        assertEquals(List.of(".github/workflows/ci.yml"), finished.blockedPaths());
    }

    @Test
    void aForgeRejectionOutranksTheClockThatRanOutAroundIt() {
        // FR-F7's own example: the forge rejects the final checkpoint. Reported as a timeout, the
        // publisher's cause and detail were dropped and the operator was sent to the agent.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_TRANSPORT_FAILED\",\"detail\":\"remote hung up\"}");

        RunResult.RunFailed failed =
                assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals(RunFailureCause.PUSH_TRANSPORT_FAILED, RunFailureCause.of(failed.cause()));
        assertTrue(failed.detail().contains("remote hung up"),
                "the publisher's own detail survives; it names what actually refused");
    }

    @Test
    void anOverrunThatPushedStillCarriesWhatTheAgentSpent() {
        // A run that spent its entire wall clock is the most expensive one the system produces. The
        // fold measured the usage, so reporting it as unknown here would lose the largest charge
        // there is — the understatement ADR-023 exists to prevent, arriving through the result.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 1200L, TokenBucket.OUTPUT, 340L));
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertTrue(finished.usageIsKnown(), "the fold measured it; the result must not answer unknown");
        assertEquals(1200L, finished.tokenUsage().get("INPUT"));
    }

    @Test
    void aPreservedUnitThatRefusesToStopStillReportsTheRun() {
        // The stop is best-effort by design: losing the terminal result would leave the run in
        // 'running' forever, which is strictly worse than a sandbox the watchdog will reap.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        runtime.cancelFails = new IllegalStateException("daemon refused the kill");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
    }

    @Test
    void teardownNeverPrecedesSalvage() {
        // Both are calls on the same object, so a reordering compiles and passes every assertion
        // about the RESULT — while destroying the unit first throws away the very thing salvage
        // exists to read. FR-F7 states the order absolutely, so it is asserted absolutely.
        launcher.launch(COMMAND, RunObserver.IGNORING);

        assertEquals(List.of("salvage", "destroy"), runtime.lifecycle,
                "destroy ran before salvage, discarding the run's outcome");
    }

    @Test
    void anOverrunIsToldApartFromADaemonFault() {
        // Both used to report SALVAGE_FAILED, so an agent that ran too long looked like broken
        // infrastructure. They are different people's problems: an overrun is the agent's doing and
        // the same prompt will overrun again, while a daemon fault may not recur.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        RunResult.RunFailed overran =
                assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals(RunFailureCause.AGENT_TIMEOUT, RunFailureCause.of(overran.cause()));
        assertFalse(overran.retryable(), "the same prompt against the same commit runs just as long");

        runtime.finalization = Finalization.faulted("daemon hung up mid-wait");
        RunResult.RunFailed faulted =
                assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals(RunFailureCause.SALVAGE_FAILED, RunFailureCause.of(faulted.cause()));
    }

    @Test
    void aThrowingSalvageStillReportsTheRunAndWhatWasPushed() {
        // Before this, a daemon fault inside salvage propagated out of launch: no terminal result,
        // the sibling log reader blocked for the life of the process, and the unit leaked unnamed.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        // The ref is a COMPONENT, not a sentence. It used to appear only inside a failure detail,
        // so the run's record carried no pushed_ref and an operator was sent looking for a branch
        // that is really on the remote — the same wrong answer the overrun path gave.
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertTrue(finished.agentUnobserved(), "nobody read the agent's exit, so the run is not complete");
        assertTrue(runtime.destroyed.isEmpty(), "and the unit is still preserved for inspection");
    }

    @Test
    void aPublisherFailureAfterAGoodAgentRunStillReportsItsSpend() {
        // The agent ran to completion and bought its tokens; only the push failed. This is the
        // scenario the change headlines -- an agent works for an hour and then has its push
        // rejected -- and it was the site nothing asserted.
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 5100L));
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"rejected\"}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals(5100L, failed.tokenUsage().get("INPUT"));
    }

    @Test
    void anOverrunThatDeliveredNothingStillReportsItsSpend() {
        // The unobserved branch's own failure path. A run that spent its entire wall clock is the
        // most expensive outcome the system produces, so this is the largest single charge
        // there is to lose.
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 88_000L));
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals(RunFailureCause.AGENT_TIMEOUT, RunFailureCause.of(failed.cause()));
        assertEquals(88_000L, failed.tokenUsage().get("INPUT"));
    }

    @Test
    void aForgeRejectionOnTheUnobservedPathStillReportsItsSpend() {
        // The third uncovered site: the publisher's own cause outranks the clock, and the spend
        // has to survive that ranking.
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 640L));
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_TRANSPORT_FAILED\",\"detail\":\"remote hung up\"}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals(640L, failed.tokenUsage().get("INPUT"));
    }

    @Test
    void aThrowingTeardownDoesNotDiscardAMeasuredRun() {
        // By the time destroy runs, the run's outcome AND its measured spend are already decided.
        // An unguarded throw propagated out of launch, was caught by the dispatcher, and rewrote a
        // successful pushed run as WORKER_FAILED with its usage dropped -- discarding exactly the
        // charge the ledger exists to keep. A leaked unit is recoverable by its label; a discarded
        // result is not.
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 3300L));
        runtime.destroyFails = new IllegalStateException("daemon blipped during teardown");
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertEquals(3300L, finished.tokenUsage().get("INPUT"));
    }

    @Test
    void aFailedRunStillReportsWhatItSpent() {
        // A failure is not a free outcome. The agent ran, the tokens were bought, and this result
        // is the only record of that spend the orchestrator will ever see -- so dropping it leaves
        // the deployment's rolling window blind to exactly the runs most likely to be run again.
        adapter.usage = UsageReport.of(Map.of(TokenBucket.INPUT, 4200L));
        runtime.finalization = Finalization.salvaged(2, "exited");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertTrue(failed.usageIsKnown(), "the fold measured it; a failure must not answer unknown");
        assertEquals(4200L, failed.tokenUsage().get("INPUT"));
    }

    @Test
    void aRunThatFailedBeforeAnythingRanReportsNoUsageAtAll() {
        // The other half, and it is a different fact rather than the same one with a zero:
        // "spent nothing" and "spent an amount nobody measured" are priced differently, so a
        // builder that defaulted to an empty map would assert every refused command was free.
        launcher.harnesses = new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                throw new IllegalArgumentException("unknown harness: " + harness);
            }
        };

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class,
                launcher.launch(COMMAND, RunObserver.IGNORING));

        assertFalse(failed.usageIsKnown());
    }

    @Test
    void aThrowingSalvageWithNothingPushedIsStillAFault() {
        // The other half: without a push there is nothing to report but the fault, and it keeps its
        // own cause rather than being softened into an outcome.
        runtime.salvageFails = new IllegalStateException("daemon went away");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals(RunFailureCause.SALVAGE_FAILED, RunFailureCause.of(failed.cause()));
        assertTrue(failed.detail().contains("daemon went away"), failed.detail());
    }

    @Test
    void aThrowingSalvageInterruptsReadersThatAreStillFollowing() throws Exception {
        // CompletableFuture.cancel(true) "has no effect": the readers it cancelled kept blocking on
        // the follow stream of a preserved unit for the life of the process — a virtual thread and
        // a daemon connection each. An ExecutorService Future's cancel(true) interrupts.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.readersBlock = true;

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

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

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
        assertEquals(1, runtime.destroyed.size(), "a salvaged unit is destroyed, reader fault or not");
    }

    @Test
    void aFailureDetailCarriesNoCredential() {
        // factory_run.failure_detail is read by an operator, and a runtime exception can quote the
        // request it made -- docker-java includes the create request, environment and all, in some
        // errors. The publisher has scrubbed its own failure lines since M0; the worker's did not.
        runtime.salvageFails = new IllegalStateException(
                "create failed: env=[SPIRE_WRITE_TOKEN=" + WRITE_SECRET + ", OPENAI_API_KEY=" + MODEL_KEY
                        + "] read=" + READ_SECRET);

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertFalse(failed.detail().contains(WRITE_SECRET), "the write token reached failure_detail");
        assertFalse(failed.detail().contains(READ_SECRET), "the read token reached failure_detail");
        assertFalse(failed.detail().contains(MODEL_KEY),
                "the model key reached failure_detail — it rides the same container environment as "
                        + "the machine account's token, and is the credential the debt entry named");
        assertTrue(failed.detail().contains("create failed"), "the diagnosis itself must survive");
    }

    @Test
    void aFailureIsRetryableOnlyWhenItsCauseIs() {
        // Every publisher failure used to be reported retryable. A push the forge rejected refuses
        // the same way next time, so the retry bought nothing and cost another whole agent run --
        // the expensive half, since the model has already been paid for by the time we get here.
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_REJECTED\",\"detail\":\"blocked by policy\"}");
        RunResult.RunFailed rejected = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals(RunFailureCause.PUSH_REJECTED, RunFailureCause.of(rejected.cause()));
        assertFalse(rejected.retryable(), "the forge answered no, and will answer no again");

        // ...and the forge never answering is the opposite call. The legacy PUSH_FAILED spelling
        // aliases here rather than to a refusal, because a transport fault is the reading that costs
        // money to get wrong: told it is a refusal, a network blip is never retried.
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"connection reset\"}");
        RunResult.RunFailed transport = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals(RunFailureCause.PUSH_TRANSPORT_FAILED, RunFailureCause.of(transport.cause()));
        assertTrue(transport.retryable(), "the push never reached the forge, so it may yet succeed");

        // ...while a clone that could not reach the forge genuinely might succeed on a retry.
        runtime.publisherLines = List.of(
                "{\"event\":\"failed\",\"cause\":\"CLONE_FAILED\",\"detail\":\"connection reset\"}");
        RunResult.RunFailed clone = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
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

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("refs/heads/spire/finding-1", finished.pushedRef());
    }

    @Test
    void aPublisherFailureOutranksACleanAgentExit() {
        runtime.publisherLines = List.of("{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"rejected\"}");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("PUSH_FAILED", failed.cause());
    }

    @Test
    void aGateRefusalIsFinishedAsRefusedNotFailed() {
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}],"
                        + "\"changed\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished = assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertTrue(finished.refused());
        assertEquals(List.of(".github/workflows/ci.yml"), finished.blockedPaths());
        assertNull(finished.pushedRef());
    }

    @Test
    void aFailedSalvagePreservesTheUnitAndSaysSo() {
        runtime.finalization = Finalization.faulted("daemon hung up mid-wait");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("SALVAGE_FAILED", failed.cause());
        assertFalse(failed.retryable());
        assertTrue(runtime.destroyed.isEmpty(), "an unsalvaged unit is kept for inspection, never destroyed");
    }

    @Test
    void aRuntimeThatCannotPlaceTheUnitIsRetryable() {
        runtime.createFails = new IllegalStateException("no daemon");

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        // RUNTIME_UNAVAILABLE rather than SANDBOX_LOST: create() failed, so nothing started and
        // nothing was lost. An operator checking a daemon and an operator reading an eviction are
        // two different people looking in two different places, which is what FR-F9 is for.
        assertEquals("RUNTIME_UNAVAILABLE", failed.cause());
        assertTrue(failed.retryable(), "a daemon comes back");
    }

    @Test
    void aBadCommandFailsBeforeAnythingIsCreatedAndIsNotRetried() {
        launcher.harnesses = new HarnessRegistry() {
            @Override
            public HarnessAdapter forName(String harness) {
                throw new IllegalArgumentException("unknown harness: " + harness);
            }
        };

        RunResult.RunFailed failed = assertInstanceOf(RunResult.RunFailed.class, launcher.launch(COMMAND, RunObserver.IGNORING));
        assertEquals("BAD_COMMAND", failed.cause());
        assertFalse(failed.retryable());
        assertTrue(runtime.destroyed.isEmpty());
    }

    /** Records what the launcher reported about the sandbox, in order. */
    private static final class RecordingObserver implements RunObserver {
        final List<String> announced = new java.util.ArrayList<>();
        boolean released;
        RuntimeException announceFails;

        @Override
        public void event(dev.codespire.contract.event.RunEventRecord record) {
        }

        @Override
        public void unitCreated(String unitId) {
            announced.add(unitId);
            if (announceFails != null) {
                throw announceFails;
            }
        }

        @Override
        public void unitReleased() {
            released = true;
        }
    }

    @Test
    void theAnnouncedUnitIsTheSandboxsOwnId() {
        // The whole point of the fix: the field is documented as the pod or container id, and it
        // used to receive the run id because the event was emitted before a handle existed.
        RecordingObserver observer = new RecordingObserver();

        launcher.launch(COMMAND, observer);

        assertEquals(List.of("unit-1"), observer.announced,
                "the fake runtime names its sandbox unit-1; the launcher must pass that, not the run id");
        assertNotEquals(COMMAND.runId(), observer.announced.getFirst(),
                "the two fields answer different questions; filling one with the other says nothing");
    }

    @Test
    void aRuntimeThatCannotPlaceTheUnitAnnouncesNothing() {
        // Otherwise the caller records a lease against a sandbox that does not exist, and a
        // watchdog goes looking for it.
        runtime.createFails = new IllegalStateException("daemon down");
        RecordingObserver observer = new RecordingObserver();

        launcher.launch(COMMAND, observer);

        assertTrue(observer.announced.isEmpty());
        assertFalse(observer.released, "nothing was created, so nothing was destroyed");
    }

    @Test
    void aThrowingAnnouncementDoesNotCostTheRunItsResult() {
        // Bookkeeping about the run, not part of it. The unit exists whether or not anybody
        // recorded that it does, and it carries the label either way.
        RecordingObserver observer = new RecordingObserver();
        observer.announceFails = new IllegalStateException("the database is down");

        assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, observer));
    }

    @Test
    void aDestroyedUnitIsReportedGone() {
        RecordingObserver observer = new RecordingObserver();

        launcher.launch(COMMAND, observer);

        assertTrue(observer.released);
    }

    @Test
    void aUnitThatCouldNotBeDestroyedIsNotReportedGone() {
        // Silence means "still there", and that default is the fix: the caller keeps the lease, so
        // the leak is a row rather than a credential-bearing container nobody can find.
        runtime.destroyFails = new IllegalStateException("daemon blipped during teardown");
        RecordingObserver observer = new RecordingObserver();

        launcher.launch(COMMAND, observer);

        assertFalse(observer.released);
    }

    @Test
    void aPreservedUnitIsNotReportedGone() {
        // The launcher never reaches destroy on this path, so the caller must hear nothing.
        runtime.finalization = Finalization.overran("agent did not exit within the run's wall clock");
        RecordingObserver observer = new RecordingObserver();

        launcher.launch(COMMAND, observer);

        assertFalse(observer.released);
        assertEquals(1, observer.announced.size(), "it did exist, and the caller needs to know which");
    }

    @Test
    void aGateRefusalStopsTheAgentRatherThanLettingItSpend() {
        // RUN-TOPOLOGY says a refusal mid-run terminates the run. What terminated was the
        // PUBLISHER: it stopped reading bundles and exited, and nothing reached the remote. The
        // agent knew none of that and kept working until it finished or its wall clock expired,
        // buying model calls for work that can never be published -- so the whole cost of the
        // refusal fell on the operator rather than on the run.
        //
        // Stopped from the publisher's reader, the moment the refusal is read, rather than at
        // salvage -- by which time the money is already spent.
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}],"
                        + "\"changed\":[{\"path\":\".github/workflows/ci.yml\",\"kind\":\"MODIFIED\"}]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertTrue(finished.refused(), "the refusal is still the run's outcome");
        assertEquals(1, runtime.cancelled.size(),
                "and the agent is stopped, so it cannot go on spending on work that cannot be published");
    }

    @Test
    void aRunThatWasNotRefusedIsNeverStopped() {
        // The other half. Without it the stop could be unconditional and every test above would
        // still pass, because none of them asserts that a healthy run runs to completion untouched.
        runtime.publisherLines = List.of(
                "{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/finding-1\",\"changed\":[]}");

        launcher.launch(COMMAND, RunObserver.IGNORING);

        assertTrue(runtime.cancelled.isEmpty());
    }

    @Test
    void theAgentIsStoppedOnceHoweverManyLinesFollowTheRefusal() {
        // The publisher keeps emitting after it refuses, and the reader is called per line. A stop
        // per line would be harmless on a healthy daemon and a burst of container kills on a slow
        // one, at the exact moment the run is already going wrong.
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"ci.yml\",\"kind\":\"MODIFIED\"}],\"changed\":[]}",
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"ci.yml\",\"kind\":\"MODIFIED\"}],\"changed\":[]}",
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"ci.yml\",\"kind\":\"MODIFIED\"}],\"changed\":[]}");

        launcher.launch(COMMAND, RunObserver.IGNORING);

        assertEquals(1, runtime.cancelled.size());
    }

    @Test
    void aRuntimeThatCannotStopTheAgentStillReportsTheRefusal() {
        // This runs on the publisher's reader thread, so throwing would end that stream and lose
        // the blocked paths the refusal is about to be reported with -- trading the operator's
        // explanation for a failed attempt at saving them money.
        runtime.cancelFails = new IllegalStateException("the daemon refused");
        runtime.publisherLines = List.of(
                "{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"ci.yml\",\"kind\":\"MODIFIED\"}],\"changed\":[]}");

        RunResult.RunFinished finished =
                assertInstanceOf(RunResult.RunFinished.class, launcher.launch(COMMAND, RunObserver.IGNORING));

        assertTrue(finished.refused());
        assertEquals(List.of("ci.yml"), finished.blockedPaths());
    }
}
