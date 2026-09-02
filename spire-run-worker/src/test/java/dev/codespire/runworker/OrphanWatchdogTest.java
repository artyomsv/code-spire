package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watchdog that reclaims a sandbox nobody owns.
 *
 * <p>The architecture is explicit that a naive version is worse than the leak it fixes: with two
 * replicas on one daemon, reaping everything the runtime enumerates destroys a sibling's live
 * hour-long run. So the runtime's listing is the INPUT and the lease is the predicate — which is why
 * the SPI method that returns that listing is now named for what it does rather than for the
 * judgement it cannot make.
 */
class OrphanWatchdogTest {

    private static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final String MINE = "this-replica";

    private static final String SIBLING = "another-replica";

    /** Lists what it is told to, and records what was salvaged and destroyed, in order. */
    private static final class FakeRuntime implements RunRuntime {
        final List<RunHandle> units = new ArrayList<>();
        final List<String> lifecycle = new ArrayList<>();
        final List<RunHandle> destroyed = new ArrayList<>();
        Finalization finalization = Finalization.salvaged(0, "exited");
        RuntimeException salvageFails;

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
            throw new UnsupportedOperationException("the watchdog never creates anything");
        }

        @Override
        public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        }

        @Override
        public void cancel(RunHandle handle) {
            lifecycle.add("cancel:" + handle.runId());
        }

        @Override
        public Finalization salvage(RunHandle handle) {
            lifecycle.add("salvage:" + handle.runId());
            if (salvageFails != null) {
                throw salvageFails;
            }
            return finalization;
        }

        @Override
        public void destroy(RunHandle handle) {
            lifecycle.add("destroy:" + handle.runId());
            destroyed.add(handle);
        }

        @Override
        public List<RunHandle> discoverUnits() {
            return List.copyOf(units);
        }

        @Override
        public Duration drainWindow() {
            return Duration.ofSeconds(1);
        }
    }

    /** Answers the leases the test declares, without a database. */
    private static class FakeLeases extends WorkspaceLeases {
        final Map<String, Lease> byRun = new LinkedHashMap<>();
        final List<String> released = new ArrayList<>();

        @Override
        public String ownerId() {
            return MINE;
        }

        @Override
        public Optional<Lease> find(String runId) {
            return Optional.ofNullable(byRun.get(runId));
        }

        @Override
        public void release(String runId) {
            released.add(runId);
            byRun.remove(runId);
        }
    }

    /** Takes every claim once, so "reported once ever" is a fact rather than a hope. */
    private static final class FakeClaims extends RunClaimStore {
        final List<String> taken = new ArrayList<>();

        @Override
        public boolean claim(String runId, String slot) {
            return taken.add(runId + ":" + slot) && taken.stream()
                    .filter(entry -> entry.equals(runId + ":" + slot)).count() == 1;
        }
    }

    private final FakeRuntime runtime = new FakeRuntime();
    private final FakeLeases leases = new FakeLeases();
    private final FakeClaims claims = new FakeClaims();
    private final List<RunResult> reported = new ArrayList<>();

    private OrphanWatchdog watchdog() {
        OrphanWatchdog watchdog = new OrphanWatchdog();
        watchdog.runtime = runtime;
        watchdog.leases = leases;
        watchdog.claims = claims;
        watchdog.reporter = reported::add;
        watchdog.staleAfterSeconds = STALE_AFTER.toSeconds();
        watchdog.heartbeatEvery = Duration.ofSeconds(30);
        return watchdog;
    }

    private void unitWithLease(String runId, String owner, Duration heartbeatAge) {
        runtime.units.add(new RunHandle(runId, "container-" + runId));
        leases.byRun.put(runId, new WorkspaceLeases.Lease(runId, owner, "container-" + runId,
                Instant.now().minus(heartbeatAge), null));
    }

    @Test
    void aSiblingsLiveRunIsNeverReaped() {
        // The failure the architecture calls worse than the leak. Two replicas share one daemon, so
        // a watchdog that acts on the runtime's listing alone destroys an hour of somebody else's
        // work. The lease is what makes the listing safe to act on.
        unitWithLease("run::github:TEST-acme/app:sibling:1", SIBLING, Duration.ofSeconds(10));

        watchdog().sweep();

        assertEquals(List.of(), runtime.lifecycle,
                "a fresh heartbeat means a live run, whoever owns it");
        assertEquals(List.of(), reported);
    }

    @Test
    void thisReplicasOwnLiveRunIsNeverReapedEither() {
        // The launcher blocks for the whole run, so the sweep and the run happen in one process.
        // Reaping our own in-flight work would be the same defect with a shorter path.
        unitWithLease("run::github:TEST-acme/app:mine:1", MINE, Duration.ofSeconds(10));

        watchdog().sweep();

        assertEquals(List.of(), runtime.lifecycle);
    }

    @Test
    void aSandboxWithNoLeaseIsAnOrphan() {
        // The control plane lost it entirely — a replica evicted between taking the claim and taking
        // the lease, or one whose lease was released while the unit survived. `staleLeases` cannot
        // see this case at all, because there is no row to be stale.
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog().sweep();

        assertEquals(List.of("salvage:run::github:TEST-acme/app:lost:1",
                        "destroy:run::github:TEST-acme/app:lost:1"),
                runtime.lifecycle);
    }

    @Test
    void aStaleHeartbeatIsAnOrphanAndAFreshOneIsNot() {
        // The boundary asserted on both sides, which is the only way a threshold test discriminates.
        unitWithLease("run::github:TEST-acme/app:stale:1", MINE, STALE_AFTER.plusMinutes(1));
        unitWithLease("run::github:TEST-acme/app:fresh:1", MINE, STALE_AFTER.minusMinutes(1));

        watchdog().sweep();

        assertTrue(runtime.destroyed.stream().anyMatch(h -> h.runId().endsWith("stale:1")));
        assertFalse(runtime.destroyed.stream().anyMatch(h -> h.runId().endsWith("fresh:1")));
    }

    @Test
    void aPreservedUnitIsActionableWithoutWaiting() {
        // A preserved unit's run is OVER, so there is nothing to wait for. Requiring it to go stale
        // first would leave a credential-bearing container up for the staleness window on every
        // timeout and every failed salvage.
        String runId = "run::github:TEST-acme/app:kept:1";
        runtime.units.add(new RunHandle(runId, "container-kept"));
        leases.byRun.put(runId, new WorkspaceLeases.Lease(runId, MINE, "container-kept",
                Instant.now(), Instant.now()));

        watchdog().sweep();

        assertTrue(runtime.destroyed.stream().anyMatch(h -> h.runId().equals(runId)),
                "a fresh heartbeat must not hide a unit that was kept on purpose");
    }

    @Test
    void reapingSalvagesBeforeDestroying() {
        // Task 3's rule holds on this path too: destroying first throws away the very thing salvage
        // exists to read, and the watchdog is the one caller most likely to be looking at a unit
        // nobody else will ever look at again.
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog().sweep();

        assertEquals(List.of("salvage:run::github:TEST-acme/app:lost:1",
                        "destroy:run::github:TEST-acme/app:lost:1"),
                runtime.lifecycle);
    }

    @Test
    void aFailedSalvageDuringReapPreservesTheSandbox() {
        // The watchdog must not become the delete path Task 3 forbids. If salvage could not read the
        // unit, destroying it removes the only remaining evidence of what the agent was doing.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog().sweep();

        assertTrue(runtime.destroyed.isEmpty(),
                "an unsalvageable unit is kept, exactly as the launcher keeps one");
    }

    @Test
    void aReapedRunGetsAClassifiedFailure() {
        // Not a silent disappearance. FR-F9: a failure with no named cause reaches an operator as a
        // row saying only that something went wrong.
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog().sweep();

        RunResult.RunFailed failed = (RunResult.RunFailed) reported.getFirst();
        assertEquals(RunFailureCause.SANDBOX_LOST, RunFailureCause.of(failed.cause()));
    }

    @Test
    void aReapedRunIsReportedOnlyOnce() {
        // A unit preserved after a failed salvage is rediscovered on every tick. Without a claim it
        // would emit the same terminal failure forever — and the run's real result, if one is still
        // coming, would arrive after a failure the orchestrator has already recorded.
        runtime.salvageFails = new IllegalStateException("daemon went away");
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));
        OrphanWatchdog watchdog = watchdog();

        watchdog.sweep();
        watchdog.sweep();
        watchdog.sweep();

        assertEquals(1, reported.size(), "one sandbox, one terminal result, however many sweeps see it");
    }

    @Test
    void aReapedRunsLeaseIsReleased() {
        // Otherwise the row outlives the container it names, and the next sweep reads a lease for a
        // sandbox that is gone.
        unitWithLease("run::github:TEST-acme/app:stale:1", MINE, STALE_AFTER.plusMinutes(1));

        watchdog().sweep();

        assertEquals(List.of("run::github:TEST-acme/app:stale:1"), leases.released);
    }

    @Test
    void anUnreadableLeaseSkipsTheUnitRatherThanReapingIt() {
        // "No lease" is the licence to reap, so a database fault answering "no lease" would destroy
        // every live run on the daemon. The sweep skips what it cannot judge; a leak is visible and
        // recoverable, a destroyed run is neither.
        FakeLeases throwing = new FakeLeases() {
            @Override
            public Optional<WorkspaceLeases.Lease> find(String runId) {
                throw new IllegalStateException("the database is down");
            }
        };
        OrphanWatchdog watchdog = watchdog();
        watchdog.leases = throwing;
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog.sweep();

        assertEquals(List.of(), runtime.lifecycle);
    }

    @Test
    void oneUnreadableUnitDoesNotStopTheSweep() {
        // A sweep that abandons its whole tick on one bad unit leaks every other orphan behind it,
        // and the next tick meets the same one first.
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:first:1", "container-first"));
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:second:1", "container-second"));
        FakeRuntime failing = runtime;
        failing.salvageFails = null;
        OrphanWatchdog watchdog = watchdog();
        watchdog.leases = new FakeLeases() {
            @Override
            public Optional<WorkspaceLeases.Lease> find(String runId) {
                if (runId.contains("first")) {
                    throw new IllegalStateException("the database blipped");
                }
                return Optional.empty();
            }
        };

        watchdog.sweep();

        assertTrue(runtime.destroyed.stream().anyMatch(h -> h.runId().contains("second")),
                "the second orphan is reclaimed even though the first could not be judged");
    }

    @Test
    void aThresholdThatDoesNotExceedTheHeartbeatRefusesToStart() {
        // One decision made in two places. A threshold at or below the heartbeat interval reaps
        // healthy runs by construction — the heartbeat has not had time to land — and the failure is
        // silent, because the watchdog looks like it is working. This repository has already shipped
        // this exact shape once, when a timeout equalled an ack threshold.
        assertThrows(IllegalStateException.class,
                () -> OrphanWatchdog.verify(Duration.ofSeconds(30), Duration.ofSeconds(30)));
        assertThrows(IllegalStateException.class,
                () -> OrphanWatchdog.verify(Duration.ofSeconds(10), Duration.ofSeconds(30)));
    }

    @Test
    void aThresholdComfortablyAboveTheHeartbeatIsAccepted() {
        // The other half. Without it the check could refuse everything and the tests above would
        // still pass, since none of them boots the application.
        OrphanWatchdog.verify(Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @Test
    void theCancelIsNotPartOfAReap() {
        // salvage() already stops what it must; a cancel before it would kill the agent whose output
        // salvage is about to read.
        runtime.units.add(new RunHandle("run::github:TEST-acme/app:lost:1", "container-lost"));

        watchdog().sweep();

        assertTrue(runtime.lifecycle.stream().noneMatch(step -> step.startsWith("cancel:")));
    }

}
