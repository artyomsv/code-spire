package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunVerification;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.runtime.*;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Worker decisions with fake storage and runtime; the containers have their own Docker proof. */
class WorkVerifyWorkerTest {
    final RunCommand.ExecuteWorkRun held = HeldRunLauncherTest.COMMAND;
    final UUID attempt = UUID.randomUUID();
    final RunCommand.VerifyWork command = new RunCommand.VerifyWork(held.runId(), held.work(), attempt, HeldRunLauncherTest.HEAD, List.of("TEST-check"), 60);
    final Map<UUID, RunCommand.VerifyWork> running = new LinkedHashMap<>();
    final List<RunVerification.RunWorkVerified> finished = new ArrayList<>(), unsent = new ArrayList<>(), reported = new ArrayList<>();
    final Set<UUID> claimed = new HashSet<>();
    final List<UUID> removed = new ArrayList<>();
    boolean heldPresent = true, unitPresent = true, revoked, reportAccepted = true, verifyThrows;
    int verifies;
    VerifyRun observation = new VerifyRun(HeldRunLauncherTest.HEAD, VerifyRun.Prepare.PREPARED, List.of(new VerifyRun.Check(0, 5, List.of("TEST-ok"))), false);

    final WorkVerifyStore store = new WorkVerifyStore() {
        // Every method the worker reaches is answered here; the parent would open a database.
        @Override public boolean claim(RunCommand.VerifyWork verify) { if (!claimed.add(verify.attemptId())) return false; running.put(verify.attemptId(), verify); return true; }
        @Override public void finish(RunVerification.RunWorkVerified result) {
            if (running.remove(result.verification().attemptId()) == null) return;
            finished.add(result); unsent.add(result);
        }
        @Override public List<RunVerification.RunWorkVerified> unsent() { return List.copyOf(unsent); }
        @Override public void sent(UUID id) { unsent.removeIf(result -> result.verification().attemptId().equals(id)); }
        @Override public List<RunCommand.VerifyWork> running() { return List.copyOf(running.values()); }
    };
    final WorkRunStore runs = new WorkRunStore() {
        @Override public Optional<Held> find(String id) {
            return heldPresent ? Optional.of(new Held(held, null, "TEST-unit", "ready", null, null, null, Instant.now())) : Optional.empty();
        }
        @Override public boolean revoked(RunCommand.ExecuteWorkRun execution) { return revoked; }
    };
    class Runtime extends RunLauncherTest.FakeRuntime implements PublicationRuntime {
        @Override public List<RunHandle> discoverUnits() { return unitPresent ? List.of(new RunHandle(held.runId(), "TEST-unit")) : List.of(); }
        @Override public RunHandle createHeld(RunUnitSpec spec, PublicationKey key) { throw new AssertionError("verify creates no build"); }
        @Override public boolean publicationHeld(RunHandle run) { return true; }
        @Override public Finalization publishHeld(RunHandle run, PublicationKey key, UUID permit, RunUnitSpec spec,
                                                  java.util.function.Consumer<String> lines, BooleanSupplier allowed) { throw new AssertionError("verify publishes nothing"); }
        @Override public void destroyHeld(RunHandle run, PublicationKey key) { throw new AssertionError("verify keeps the held build"); }
        @Override public VerifyRun verifyHeld(RunHandle run, PublicationKey key, VerifyUnitSpec spec, BooleanSupplier mayContinue) {
            verifies++;
            assertEquals(held.work().publicationKey(), key.value());
            if (verifyThrows) throw new IllegalStateException("TEST daemon refused");
            return observation;
        }
        @Override public void removeVerify(RunHandle run, UUID id) { removed.add(id); }
    }
    final WorkVerifyWorker worker = new WorkVerifyWorker();

    @BeforeEach void wire() {
        worker.store = store;
        worker.runs = runs;
        worker.runtime = new Runtime();
        worker.builder = new RunUnitBuilder() {
            @Override public VerifyUnitSpec verifyUnit(RunCommand.ExecuteWorkRun execution, RunCommand.VerifyWork verify) { return null; /* the fake runtime reads no topology */ }
        };
        worker.reporter = new RunVerificationReporter() {
            @Override public boolean report(RunVerification result) { if (reportAccepted) reported.add((RunVerification.RunWorkVerified) result); return reportAccepted; }
        };
    }

    void execute(RunCommand.VerifyWork verify) {
        worker.execute(Message.of((RunCommand) verify, () -> CompletableFuture.completedFuture(null)), verify).toCompletableFuture().join();
    }
    WorkVerification only() { assertEquals(1, finished.size()); return finished.getFirst().verification(); }

    @Test void aPassingVerifyIsStoredThenReported() {
        execute(command);
        assertTrue(only().passed());
        assertEquals(1, reported.size());
        assertEquals(List.of(attempt), removed, "the verify unit is removed after it ran");
    }
    @Test void aRedeliveredVerifyClaimsOnceAndRunsOnce() {
        execute(command);
        execute(command);
        assertEquals(1, verifies);
        assertEquals(1, finished.size());
    }
    @Test void aHeldRunThisWorkerDoesNotHoldIsCheckpointMissing() {
        heldPresent = false;
        execute(command);
        assertEquals("checkpoint_missing", only().reason());
        assertEquals(0, verifies);
    }
    @Test void aMissingLocalUnitIsCheckpointMissing() {
        unitPresent = false;
        execute(command);
        assertEquals("checkpoint_missing", only().reason());
    }
    @Test void aRevokedHeldBuildCouldNotRun() {
        revoked = true;
        execute(command);
        assertEquals("verify_could_not_run", only().reason());
        assertEquals(0, verifies);
    }
    @Test void noCommandsRunsNothing() {
        execute(new RunCommand.VerifyWork(held.runId(), held.work(), attempt, HeldRunLauncherTest.HEAD, List.of(), 60));
        assertEquals("no_checks_declared", only().reason());
        assertEquals(0, verifies);
    }
    @Test void aVerifyThatThrowsCouldNotRunAndIsStillRemoved() {
        verifyThrows = true;
        execute(command);
        assertEquals("verify_could_not_run", only().reason());
        assertEquals(List.of(attempt), removed);
    }
    @Test void aVerifyLostToARestartIsReportedAsCouldNotRun() {
        running.put(attempt, command);
        worker.recover();
        assertEquals("verify_could_not_run", only().reason());
        assertEquals(List.of(attempt), removed);
        assertEquals(1, reported.size());
    }
    @Test void aRunningVerifyIsNotRecoveredUnderItself() {
        observation = null;
        worker.runtime = new Runtime() {
            @Override public VerifyRun verifyHeld(RunHandle run, PublicationKey key, VerifyUnitSpec spec, BooleanSupplier mayContinue) {
                worker.recover();   // a recovery sweep while this verify is still running
                return new VerifyRun(HeldRunLauncherTest.HEAD, VerifyRun.Prepare.PREPARED, List.of(new VerifyRun.Check(0, 5, List.of())), false);
            }
        };
        execute(command);
        assertTrue(only().passed(), "the live verify's own result stands");
    }
    @Test void anUnacknowledgedResultStaysUnsentAndIsResent() {
        reportAccepted = false;
        execute(command);
        assertEquals(1, unsent.size());
        reportAccepted = true;
        worker.flush();
        assertTrue(unsent.isEmpty());
        assertEquals(1, reported.size());
    }
}
