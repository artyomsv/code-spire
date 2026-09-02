package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reclaims a sandbox nobody owns, and only one nobody owns.
 *
 * <p><b>A naive version is worse than the leak it fixes.</b> Two replicas can share one daemon, so
 * acting on the runtime's listing alone destroys a sibling's live hour-long run. The listing is the
 * INPUT; the lease is the predicate — which is why the SPI method that produces it is named
 * {@link RunRuntime#discoverUnits()} rather than for a judgement a runtime has no way to make.
 *
 * <p>A unit is an orphan when its lease is <b>absent</b> (the control plane lost it entirely — a
 * replica evicted between claim and lease, or a lease released while the unit survived), when its
 * lease is <b>preserved</b> (the run is over and the sandbox was deliberately kept, so there is
 * nothing left to wait for), or when its heartbeat is <b>older than the staleness window</b>.
 *
 * <p>Reaping salvages before it destroys, and a failed salvage keeps the unit — the watchdog must not
 * become the delete path that rule forbids, and it is the caller most likely to be looking at
 * something nobody will ever look at again.
 *
 * <p>Every decision fails in the direction that destroys nothing. A lease that cannot be read skips
 * its unit rather than reading the silence as "unowned"; one bad unit does not abandon the tick.
 */
@ApplicationScoped
public class OrphanWatchdog {

    private static final Logger LOG = Logger.getLogger(OrphanWatchdog.class);

    /**
     * The slot the terminal report is claimed under.
     *
     * <p>Distinct from the execute slot on purpose: the run may have been claimed and executed
     * normally, and this is a second, later fact about the same run. A constant rather than anything
     * per-sweep, because it must make the report once EVER — a unit preserved after a failed salvage
     * is rediscovered on every tick, and without the claim it would emit the same terminal failure
     * forever.
     */
    static final String REAP_SLOT = "reap";

    @Inject
    RunRuntime runtime;

    @Inject
    WorkspaceLeases leases;

    @Inject
    RunClaimStore claims;

    /**
     * Where a reaped run's terminal result goes.
     *
     * <p>A {@link Consumer} rather than the emitter itself, so the sweep can be driven without a
     * broker. The dispatcher owns publishing; this owns deciding.
     */
    @Inject
    RunResultReporter results;

    Consumer<RunResult> reporter;

    @ConfigProperty(name = "spire.run.orphan-stale-after-seconds", defaultValue = "600")
    long staleAfterSeconds;

    /**
     * The heartbeat interval, read so the pair can be checked rather than assumed.
     *
     * <p>The staleness threshold and the heartbeat interval are ONE decision made in two places, and
     * a threshold that does not exceed the interval reaps healthy runs by construction.
     */
    @ConfigProperty(name = "spire.run.lease-heartbeat", defaultValue = "30s")
    Duration heartbeatEvery;

    void check(@Observes StartupEvent event) {
        verify(Duration.ofSeconds(staleAfterSeconds), heartbeatEvery);
    }

    /**
     * The rule, separable from CDI so a test can drive it with values that match nothing shipped.
     *
     * <p>The margin is deliberate rather than a strict inequality: a threshold one second above the
     * interval means a single delayed sweep condemns a live run, and the sweep is best-effort by
     * design — a lease write that fails is logged and skipped. Several missed heartbeats have to be
     * survivable, because the alternative is destroying work that is still happening.
     */
    static void verify(Duration staleAfter, Duration heartbeat) {
        Duration needed = heartbeat.multipliedBy(MISSED_HEARTBEATS_TOLERATED);
        if (staleAfter.compareTo(needed) < 0) {
            throw new IllegalStateException("spire.run.orphan-stale-after-seconds is "
                    + staleAfter.toSeconds() + "s but the lease heartbeat runs every "
                    + heartbeat.toSeconds() + "s, so a live run must survive at least "
                    + MISSED_HEARTBEATS_TOLERATED + " missed sweeps (" + needed.toSeconds() + "s). "
                    + "A shorter threshold reaps healthy runs, and the watchdog looks like it is working "
                    + "while it does so.");
        }
    }

    /** How many consecutive missed heartbeats a live run must survive before it looks abandoned. */
    static final int MISSED_HEARTBEATS_TOLERATED = 4;

    /**
     * One pass over the daemon's units.
     *
     * <p>Scheduled at the staleness window rather than at the heartbeat interval: sweeping faster
     * than a unit can become an orphan only costs listings.
     */
    @Scheduled(every = "${spire.run.orphan-sweep:5m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        for (RunHandle unit : units()) {
            try {
                reapIfOrphaned(unit);
            } catch (RuntimeException e) {
                // One unit that cannot be judged must not abandon the tick: every orphan behind it
                // would leak, and the next tick meets the same one first.
                LOG.errorf(e, "run %s: this unit could not be examined; leaving it for the next sweep",
                        unit.runId());
            }
        }
    }

    private Iterable<RunHandle> units() {
        try {
            return runtime.discoverUnits();
        } catch (RuntimeException e) {
            LOG.errorf(e, "the run units could not be listed; nothing is being reclaimed this tick");
            return java.util.List.of();
        }
    }

    private void reapIfOrphaned(RunHandle unit) {
        Optional<WorkspaceLeases.Lease> lease = leases.find(unit.runId());
        if (lease.isPresent() && !isOrphaned(lease.orElseThrow())) {
            return;
        }
        if (!claims.claim(unit.runId(), REAP_SLOT)) {
            // Already reported. A preserved unit is rediscovered on every tick, and a second terminal
            // result would overwrite the first with a later timestamp and no new information.
            LOG.debugf("run %s: already reported as reclaimed", unit.runId());
            return;
        }
        LOG.warnf("run %s: reclaiming a sandbox with %s", unit.runId(),
                lease.map(l -> l.preserved() ? "a lease marked preserved" : "a lease nobody has heartbeated")
                        .orElse("no lease at all"));
        reap(unit);
    }

    /**
     * Whether this lease means the sandbox may be reclaimed.
     *
     * <p>Preserved first, and not folded into staleness: a preserved unit's run is OVER, so waiting
     * for its heartbeat to age would leave a credential-bearing container up for the whole staleness
     * window after every timeout and every failed salvage.
     */
    private boolean isOrphaned(WorkspaceLeases.Lease lease) {
        return lease.preserved()
                || lease.heartbeatAt().isBefore(Instant.now().minusSeconds(staleAfterSeconds));
    }

    private void reap(RunHandle unit) {
        Finalization finalization;
        try {
            // Salvage BEFORE destroy, the same rule the launcher keeps. Destroying first throws away
            // exactly what salvage exists to read, and this is the caller most likely to be looking
            // at a unit nobody will ever look at again.
            finalization = runtime.salvage(unit);
        } catch (RuntimeException e) {
            // The watchdog must not become the delete path that rule forbids. A unit salvage could
            // not read keeps its containers, and its lease is left alone so the next sweep still
            // finds it — the claim above is what stops it being REPORTED a second time.
            LOG.errorf(e, "run %s: its salvage failed during reclamation; the sandbox is kept", unit.runId());
            report(unit, "its sandbox was reclaimed but could not be read; the unit is preserved");
            return;
        }
        report(unit, finalization.salvaged()
                ? "its sandbox outlived the control plane and was reclaimed (exit " + finalization.exitCode() + ")"
                : "its sandbox outlived the control plane and was reclaimed; " + finalization.detail());
        destroy(unit);
        leases.release(unit.runId());
    }

    private void destroy(RunHandle unit) {
        try {
            runtime.destroy(unit);
        } catch (RuntimeException e) {
            // The lease stays, so the next sweep sees it again; the claim stops a second report.
            LOG.errorf(e, "run %s: its sandbox could not be destroyed during reclamation", unit.runId());
        }
    }

    /**
     * Say what happened, with a cause.
     *
     * <p>{@code SANDBOX_LOST} rather than a bare failure: FR-F9's rule is that a failure with no
     * named cause reaches an operator as a row saying only that something went wrong, and this one
     * has a specific, actionable meaning — the run outlived whatever was supposed to be watching it.
     * It is retryable, because a replica eviction says nothing about the work.
     */
    private void report(RunHandle unit, String detail) {
        RunResult.RunFailed failed = new RunResult.RunFailed(unit.runId(),
                RunFailureCause.SANDBOX_LOST.name(), detail,
                RunFailureCause.SANDBOX_LOST.isRetryable(),
                // Unknown, and it stays unknown: nothing here measured what the agent spent, and a
                // zero would price a reclaimed run as free.
                null);
        if (reporter != null) {
            reporter.accept(failed);
            return;
        }
        results.report(failed);
    }
}
