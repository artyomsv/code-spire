package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import dev.codespire.contract.event.RunEventRecord;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancel that actually cancels.
 *
 * <p>The command branch used to log the request and return: {@code RunRuntime.cancel} existed and
 * worked, and nothing called it from dispatch. An operator cancelling a run got no error and no
 * effect, and the run kept spending until its wall clock — the same failure shape as the silent turn
 * cap this project has already learned from, where a no-op is indistinguishable from a lost message.
 *
 * <p>The obstacle beyond wiring is the channel. {@code run-commands-in} is ordered and blocking, and
 * the launcher holds it for the run's whole duration, so a cancel delivered there would be read only
 * once the run it cancels had already finished. Control has its own topic and its own listener.
 */
class RunControlListenerTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    private static final RunCommand.CancelRun CANCEL =
            new RunCommand.CancelRun(RUN_ID, "the operator asked");

    /** Records what was cancelled, and can refuse to. */
    private static final class FakeRuntime implements RunRuntime {
        final List<RunHandle> cancelled = new ArrayList<>();
        RuntimeException cancelFails;

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
            throw new UnsupportedOperationException("the control listener never creates anything");
        }

        @Override
        public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        }

        @Override
        public void cancel(RunHandle handle) {
            cancelled.add(handle);
            if (cancelFails != null) {
                throw cancelFails;
            }
        }

        final List<String> steered = new ArrayList<>();

        RuntimeException steerFails;

        @Override
        public void steer(RunHandle handle, String instruction) {
            steered.add(instruction);
            if (steerFails != null) {
                throw steerFails;
            }
        }

        @Override
        public Finalization salvage(RunHandle handle) {
            throw new UnsupportedOperationException("cancel is not salvage: the launcher does that");
        }

        @Override
        public void destroy(RunHandle handle) {
            throw new UnsupportedOperationException("cancel is not delete");
        }

        @Override
        public List<RunHandle> discoverUnits() {
            return List.of();
        }

        @Override
        public Duration drainWindow() {
            return Duration.ofSeconds(1);
        }
    }

    private final FakeRuntime runtime = new FakeRuntime();
    private final RunRegistry registry = new RunRegistry();

    /** Declares whatever the test needs, so a capability is a value rather than a fixture. */
    private static final class FakeHarness implements dev.codespire.harness.HarnessAdapter {
        boolean steers;

        @Override
        public dev.codespire.harness.HarnessType type() {
            return dev.codespire.harness.HarnessType.CODEX;
        }

        @Override
        public dev.codespire.harness.HarnessCapabilities capabilities() {
            return new dev.codespire.harness.HarnessCapabilities(true, true, steers, false, true);
        }

        @Override
        public dev.codespire.harness.PromptDelivery promptDelivery() {
            return dev.codespire.harness.PromptDelivery.STDIN;
        }

        @Override
        public List<String> command(dev.codespire.harness.HarnessInvocation invocation) {
            return List.of();
        }

        @Override
        public java.util.Map<String, String> environment(dev.codespire.harness.HarnessInvocation invocation) {
            return java.util.Map.of();
        }

        @Override
        public java.util.Optional<dev.codespire.harness.RunEvent> parse(String line) {
            return java.util.Optional.empty();
        }

        @Override
        public dev.codespire.harness.TerminalOutcome classify(int exitCode,
                                                              dev.codespire.harness.RunEventSummary seen) {
            return dev.codespire.harness.TerminalOutcome.success("ok");
        }

        @Override
        public dev.codespire.harness.UsageReport usage(dev.codespire.harness.RunEventSummary seen) {
            return dev.codespire.harness.UsageReport.unknown();
        }
    }

    /**
     * Collects the transcript lines an operator would see.
     *
     * <p>Vetoed: the parent is an application-scoped bean, and a subclass the container also
     * discovers makes every injection of it ambiguous.
     */
    @jakarta.enterprise.inject.Vetoed
    private class NoteCollector extends RunTranscript {

        @Override
        public void note(String runId, String kind, String text, boolean error) {
            notes.add(new RunEventRecord(runId, notes.size() + 1L, java.time.Instant.now(),
                    kind, text, error));
        }
    }

    private final FakeHarness harness = new FakeHarness();

    /** Collects the transcript lines an operator would see. */
    private final List<RunEventRecord> notes = new ArrayList<>();

    private RunControlListener listener() {
        RunControlListener listener = new RunControlListener();
        listener.runtime = runtime;
        listener.registry = registry;
        listener.transcript = new NoteCollector();
        listener.harnesses = new HarnessRegistry() {
            @Override
            public dev.codespire.harness.HarnessAdapter forName(String name) {
                return harness;
            }
        };
        return listener;
    }

    @Test
    void aCancelStopsTheRunningSandbox() {
        // The whole defect: the branch logged and returned, so cancel had no error and no effect.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(CANCEL);

        assertEquals(1, runtime.cancelled.size());
        assertEquals("container-abc123", runtime.cancelled.getFirst().providerRunId());
    }

    @Test
    void aCancelNeitherSalvagesNorDestroys() {
        // Cancel is not delete. The launcher is still blocked on the agent's exit, so stopping the
        // containers makes that call return and the ordinary terminal path runs — which already
        // salvages before it destroys. Doing either here would race the launcher for the same
        // streams, or throw away checkpoints the run had already pushed.
        //
        // Asserted by the fake throwing on both: if the listener ever reaches them, this fails.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(CANCEL);

        assertEquals(1, runtime.cancelled.size());
    }

    @Test
    void aCancelForAnUnknownRunIsHarmless() {
        // Late, duplicate, or another replica's. A listener that failed on any of those would stop
        // delivering the cancels that still matter.
        listener().onControl(CANCEL);

        assertTrue(runtime.cancelled.isEmpty());
    }

    @Test
    void aCancelIsRecordedSoTheRunIsReportedCancelledNotBroken() {
        // Stopping the containers and saying WHY the run ended are two different facts. Without the
        // record the launcher classifies the killed agent as an ordinary non-zero exit, and an
        // operator who cancelled a run is told it failed.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(CANCEL);

        assertTrue(registry.wasCancelled(RUN_ID));
    }

    @Test
    void aSecondCancelIsAcceptedAndChangesNothing() {
        // Two cancels record one cancellation. The second is a duplicate delivery or an impatient
        // operator; neither should fail the channel.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));
        RunControlListener listener = listener();

        listener.onControl(CANCEL);
        listener.onControl(CANCEL);

        assertTrue(registry.wasCancelled(RUN_ID));
        assertEquals(2, runtime.cancelled.size(),
                "stopping an already-stopped sandbox is harmless; refusing to would leave a run "
                        + "alive when the first stop had failed");
    }

    @Test
    void aRuntimeThatRefusesToStopDoesNotFailTheChannel() {
        // Nacking would redeliver the record and try to cancel a run that may by then have ended
        // normally — and the cancellation is already recorded, so the result says so either way.
        runtime.cancelFails = new IllegalStateException("the daemon refused");
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(CANCEL);

        assertTrue(registry.wasCancelled(RUN_ID));
    }

    @Test
    void aPoisonRecordIsSkipped() {
        // The never-throw deserializer yields null for a record it cannot read.
        listener().onControl(null);

        assertTrue(runtime.cancelled.isEmpty());
    }

    @Test
    void anExecuteOnTheControlTopicIsRefusedLoudlyRatherThanRun() {
        // Silently ignoring it would be a run that never happens and nobody is told about; running
        // it here would put an hour-long agent on the non-blocking control channel.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-acme", "app"),
                "https://example.invalid/TEST-acme/app.git", "main", "abc1234", "spire/x", "do the thing",
                "codex", "TEST-MODEL", "spire-agent:latest", List.of(), 60, "scm", "harness"));

        assertTrue(runtime.cancelled.isEmpty(), "an execute must not be treated as a cancel");
        assertFalse(registry.wasCancelled(RUN_ID));
    }

    @Test
    void aFinishedRunIsForgottenSoALateCancelReachesNothing() {
        // The registry holds live work only. A cancel for a finished run must not resurrect a handle
        // whose containers are already destroyed.
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));
        registry.forget(RUN_ID);

        listener().onControl(CANCEL);

        assertTrue(runtime.cancelled.isEmpty());
        assertEquals(0, registry.size());
    }

    private static final RunCommand.SteerRun STEER =
            new RunCommand.SteerRun(RUN_ID, "prefer the smaller change");

    @Test
    void aHarnessDeclaringSteerReceivesTheInstruction() {
        harness.steers = true;
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(STEER);

        assertEquals(List.of("prefer the smaller change"), runtime.steered);
    }

    @Test
    void aHarnessNotDeclaringSteerRefusesRatherThanDropping() {
        // The gate is the HARNESS's fact, not the runtime's: a one-shot agent has no session to
        // steer whatever the container could carry. An operator who steers a run and sees nothing
        // cannot tell "not supported" from "the message was lost", and the second sends them looking
        // for a broker fault that is not there.
        harness.steers = false;
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(STEER);

        assertTrue(runtime.steered.isEmpty(),
                "the instruction must not reach a runtime whose harness cannot use it");
    }

    @Test
    void steeringARunThisReplicaIsNotExecutingIsRefused() {
        // Finished, or another replica's. Either way there is nothing to steer, and the refusal is
        // said out loud rather than looking like delivery.
        harness.steers = true;

        listener().onControl(STEER);

        assertTrue(runtime.steered.isEmpty());
    }

    @Test
    void aRuntimeThatCannotDeliverDoesNotFailTheChannel() {
        // The backstop firing means a harness declared a capability its runtime does not have --
        // a deployment fault worth naming, not a reason to stop delivering every later control
        // record including the cancels.
        harness.steers = true;
        runtime.steerFails = new UnsupportedOperationException("no open input stream");
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(STEER);

        assertEquals(1, runtime.steered.size(), "the attempt was made and refused, not skipped");
    }

    @Test
    void anInstructionIsBoundedAndNeverEmpty() {
        // The same ceiling the dispatch prompt has, for the same reasons: it rides every copy of
        // the command, the dead-letter row and the transcript. An empty one would reach the agent
        // as a turn that says nothing and cost a model call.
        assertThrows(IllegalArgumentException.class,
                () -> new RunCommand.SteerRun(RUN_ID, "x".repeat(RunCommand.SteerRun.MAX_INSTRUCTION_CHARS + 1)));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.SteerRun(RUN_ID, "  "));
        assertThrows(IllegalArgumentException.class, () -> new RunCommand.SteerRun(RUN_ID, null));
    }

    @Test
    void aSteerIsRecordedWhereTheRunIsWatched() {
        // A steer changes what the agent does, so a transcript showing only the agent's own output
        // would make the change look spontaneous. An operator has to be able to see that a human
        // intervened.
        harness.steers = true;
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(STEER);

        assertEquals(List.of("STEERED"), notes.stream().map(RunEventRecord::kind).toList());
        assertEquals("prefer the smaller change", notes.getFirst().text());
    }

    @Test
    void aRefusedSteerIsRecordedToo() {
        // The half that matters more. An operator who steers a run and sees nothing on its timeline
        // cannot tell "not supported" from "the message never arrived", and the second sends them
        // looking for a broker fault that is not there.
        harness.steers = false;
        registry.register(RUN_ID, "codex", new RunHandle(RUN_ID, "container-abc123"));

        listener().onControl(STEER);

        assertEquals(List.of("STEER_REFUSED"), notes.stream().map(RunEventRecord::kind).toList());
        assertTrue(notes.getFirst().error(), "a refusal is an error line, so it stands out on the tail");
    }


}
