package dev.codespire.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules every {@link RunRuntime} arm must obey, executable rather than prose.
 *
 * <p>Each arm's own test class extends this and supplies a runtime plus two unit specs. It exists
 * because there was no contract at all: three independent {@code FakeRuntime} classes stood in for
 * one, each satisfying only what its own caller's test needed, and every invariant the callers rely
 * on was enforced solely by the Docker arm's integration test. A Kubernetes arm (M5) would have been
 * written against three fakes that disagree with each other and with the one real implementation.
 *
 * <p>{@code spire-harness} is the worked example — {@code HarnessAdapterContract} — and this is that
 * pattern applied to the other SPI.
 *
 * <p><b>Every rule below was a real defect first.</b> {@code destroy} once swallowed every removal
 * error; {@code cancel} once destroyed rather than stopping, throwing away pushed checkpoints;
 * {@code attach} once split a line across two frames; the publisher was once SIGTERMed the instant
 * the agent exited, so every runtime-driven run reported nothing. None of those is visible to a fake.
 *
 * <p><b>What this deliberately does NOT cover, so it is not mistaken for complete.</b> Read-only
 * mounts, the corporate CA bundle reaching all three containers, the registry credential staying out
 * of every container, and the sandbox limits are asserted by the Docker arm's own tests. They need
 * fixtures — a shell, a writable host path, a private registry — that a contract cannot ask of an
 * arm without describing one arm's world. When a second arm lands, whichever of those it can express
 * generically belongs here.
 */
public abstract class RunRuntimeContract {

    /** The arm under test. */
    protected abstract RunRuntime runtime();

    /**
     * A unit whose agent and publisher both start, do nothing of note and exit 0 promptly.
     *
     * @param runId the id the arm must place the unit under, so {@link RunRuntime#discoverUnits()}
     *              can find it again
     */
    protected abstract RunUnitSpec quietUnit(String runId);

    /**
     * A unit whose agent writes {@code marker} to stdout as one whole line, then exits 0.
     *
     * <p>Separate from {@link #quietUnit} because the arm decides how its containers produce output,
     * and a contract that hard-coded {@code echo} would be describing a shell rather than a runtime.
     */
    protected abstract RunUnitSpec echoingUnit(String runId, String marker);

    private final List<RunHandle> placed = new CopyOnWriteArrayList<>();

    @AfterEach
    void destroyEveryUnitThisContractPlaced() {
        // A failed assertion must not leak a unit holding a model credential. Each destroy is
        // guarded so one failure does not skip the rest.
        for (RunHandle handle : placed) {
            try {
                runtime().destroy(handle);
            } catch (RuntimeException alreadyGone) {
                // Destroying an already-destroyed unit is the ordinary case here; the tests below
                // assert the property itself.
            }
        }
        placed.clear();
    }

    private RunHandle place(RunUnitSpec spec) {
        RunHandle handle = runtime().create(spec);
        placed.add(handle);
        return handle;
    }

    @Test
    void aPlacedUnitIsDiscoverableByItsRunId() {
        RunHandle handle = place(quietUnit("contract_discoverable"));

        assertTrue(runIds().contains(handle.runId()),
                "a unit this runtime is holding must be discoverable: the orphan watchdog reconciles "
                        + "what the runtime reports against the lease store, so a unit missing from "
                        + "this list is a sandbox holding a live credential that nothing can reclaim");
    }

    @Test
    void destroyLeavesNothingBehind() {
        RunHandle handle = place(quietUnit("contract_destroyed"));

        runtime().destroy(handle);

        assertFalse(runIds().contains(handle.runId()),
                "destroy must remove the unit from what the runtime reports, with no memory of it "
                        + "kept anywhere else — a destroy that only forgets locally leaves the "
                        + "containers running");
    }

    @Test
    void salvageNeverDestroys() {
        RunHandle handle = place(quietUnit("contract_salvaged"));

        Finalization finalization = runtime().salvage(handle);

        assertNotNull(finalization, "salvage must report what it took, even when that is nothing");
        assertTrue(runIds().contains(handle.runId()),
                "salvage must leave the unit in place. Teardown is the step that cannot be undone, "
                        + "and a salvage that destroys makes preserving a failed run's evidence "
                        + "impossible — which is the entire reason these are two calls");
    }

    @Test
    void cancelStopsAUnitWithoutDestroyingIt() {
        RunHandle handle = place(quietUnit("contract_cancelled"));

        runtime().cancel(handle);

        assertTrue(runIds().contains(handle.runId()),
                "cancel is not delete. A cancelled run may already have pushed checkpoints, and its "
                        + "evidence is salvaged on the ordinary terminal path afterwards");
    }

    @Test
    void cancelIsIdempotent() {
        RunHandle handle = place(quietUnit("contract_cancelled_twice"));
        runtime().cancel(handle);

        // A control record that is redelivered arrives at a run that may have ended in between, and
        // a second cancel that threw would escape into a channel configured to ignore failures.
        assertDoesNotThrow(() -> runtime().cancel(handle));
    }

    @Test
    void attachDeliversAWholeLine() {
        String marker = "CONTRACT-MARKER-" + System.nanoTime();
        RunHandle handle = place(echoingUnit("contract_attached", marker));

        List<String> lines = new ArrayList<>();
        runtime().attach(handle, LogChannel.AGENT, lines::add);

        assertTrue(lines.contains(marker),
                "attach must deliver whole lines regardless of how the transport frames them. A line "
                        + "split across two frames reaches the harness parser as two unparseable "
                        + "fragments, and a harness that cannot parse its own output reports nothing "
                        + "after the model has been paid. Saw: " + lines);
    }

    @Test
    void steerThrowsExactlyWhenTheRuntimeDoesNotDeclareIt() {
        RunHandle handle = place(quietUnit("contract_steered"));

        // The one production-shaped reader RuntimeCapabilities has. Its javadoc says "the domain
        // reads these; it never branches on RuntimeType", and for a long time nothing read them at
        // all: a record that looks like a decision point and is not. Asserting the declaration
        // against the behaviour is what makes the declaration mean something.
        if (runtime().capabilities().steering()) {
            assertDoesNotThrow(() -> runtime().steer(handle, "a further instruction"),
                    "this runtime declares steering, so it must accept an instruction");
            return;
        }
        assertThrows(UnsupportedOperationException.class,
                () -> runtime().steer(handle, "a further instruction"),
                "a runtime that cannot reach a running agent's input must throw rather than silently "
                        + "swallow an operator's instruction. There is deliberately no default "
                        + "implementation, so two layers cannot each assume the other checked");
    }

    @Test
    void theDrainWindowIsARealDuration() {
        Duration window = runtime().drainWindow();

        assertNotNull(window, "the worker's ack budget adds this to the run's wall clock");
        assertFalse(window.isNegative() || window.isZero(),
                "zero would stop the publisher the instant the agent exits, which is exactly the "
                        + "defect this value exists to prevent: every runtime-driven run reported "
                        + "nothing while a hand-driven unit pushed");
    }

    @Test
    void theRuntimeNamesItselfAndItsCapabilities() {
        assertNotNull(runtime().type(), "an arm must say what it is");
        assertNotNull(runtime().capabilities(), "capabilities are declared, never inferred by a caller");
    }

    @Test
    void theContractPlacesUnitsUnderTheRunIdItWasGiven() {
        // Guards the guard. If an arm's quietUnit ignored the run id, every discovery assertion
        // above would be checking a different unit's presence and could pass while the arm was
        // wrong. This is the same vacuity trap the repo's source scans each carry a guard for.
        RunHandle handle = place(quietUnit("contract_identity"));

        assertEquals("contract_identity", handle.runId(),
                "the handle must carry the run id the spec asked for, or nothing downstream can "
                        + "correlate a sandbox with its lease");
    }

    private List<String> runIds() {
        return runtime().discoverUnits().stream().map(RunHandle::runId).toList();
    }
}
