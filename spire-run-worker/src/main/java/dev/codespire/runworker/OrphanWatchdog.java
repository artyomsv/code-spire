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
import org.jboss.logging.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reclaims a sandbox nobody owns, and only one nobody owns.
 *
 * <p><b>A naive version is worse than the leak it fixes.</b> Two replicas can share one daemon, so
 * acting on the runtime's listing alone destroys a sibling's live hour-long run. The listing is the
 * INPUT; the lease is the predicate — which is why the SPI method that produces it is named
 * {@link RunRuntime#discoverUnits()} rather than for a judgement a runtime has no way to make.
 *
 * <p>A unit is an orphan when its lease is <b>absent</b> (the control plane lost it entirely), when
 * its lease is <b>preserved</b> (the run is over and the sandbox was deliberately kept, so there is
 * nothing left to wait for), or when its heartbeat is <b>older than the staleness window</b>. A run
 * this process is executing is never an orphan whatever the lease says — that fact is local and
 * exact, where the heartbeat is best-effort and a database outage ages it while the run works
 * perfectly well.
 *
 * <p>Reaping salvages before it destroys, and <b>a salvage that did not observe the agent keeps the
 * unit</b> — the watchdog must not become the delete path that rule forbids, and it is the caller
 * most likely to be looking at something nobody will ever look at again. The decision is made on the
 * salvage's own VALUE, not on whether it threw: the shipped runtime reports a fault as data far more
 * often than it raises one, so branching on the exception left the rule inoperative in production.
 *
 * <p>Every decision fails in the direction that destroys nothing. A lease that cannot be read skips
 * its unit rather than reading the silence as "unowned"; one bad unit does not abandon the tick; a
 * teardown that failed keeps its lease, so the next sweep tries again.
 */
@ApplicationScoped
public class OrphanWatchdog {

    private static final Logger LOG = Logger.getLogger(OrphanWatchdog.class);

    /** The dispatcher owns it. Two constants for one MDC key are two things that can drift. */
    private static final String RUN_ID_MDC = RunDispatcher.RUN_ID_MDC;

    /**
     * The slot the terminal report is claimed under.
     *
     * <p>Distinct from the execute slot on purpose: the run may have been claimed and executed
     * normally, and this is a second, later fact about the same run.
     *
     * <p><b>The claim bounds the REPORT, never the attempt.</b> Around the whole reap it was being
     * asked to carry two properties, and only one is real: re-emitting the same terminal failure
     * every tick is noise worth preventing, but declining to RETRY a teardown is how one transient
     * daemon blip permanently disqualifies a credential-bearing sandbox from ever being reclaimed —
     * with the mechanism built to find it logging "already reported" at DEBUG, where nobody looks.
     */
    static final String REAP_SLOT = "reap";

    /**
     * How many consecutive missed heartbeats a live run must survive before it looks abandoned.
     *
     * <p>A floor rather than a target: the shipped defaults give twenty, so nothing near the boundary
     * ships and this only binds an operator who tightens the threshold. It covers a DELAYED sweep. It
     * cannot cover a database outage, because no multiplier can — that is what the in-flight guard is
     * for.
     */
    static final int MISSED_HEARTBEATS_TOLERATED = 4;

    @Inject
    RunRuntime runtime;

    @Inject
    WorkspaceLeases leases;

    @Inject
    RunClaimStore claims;

    /**
     * The runs this process is executing, which are never orphans.
     *
     * <p>Local, exact, and impossible to be wrong about — unlike the heartbeat, which
     * {@code WorkspaceLeases} writes best-effort and skips on a database fault. Without this, an
     * outage longer than the staleness window ages every lease this replica holds while its runs
     * carry on working, and the sweep destroys an hour of paid work under a launcher that is blocked
     * waiting for it.
     */
    @Inject
    RunRegistry registry;

    @Inject
    RunResultReporter results;

    /**
     * Whether the sweep runs at all.
     *
     * <p>It destroys containers, on a timer, and it is the first thing in this module that does. The
     * project's own precedent for a feature with that blast radius is an off switch, so an operator
     * seeing it misbehave has a control rather than a workaround. Logged once at startup when off,
     * so it cannot be disabled by accident and forgotten.
     */
    @ConfigProperty(name = "spire.run.orphan-watchdog-enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "spire.run.orphan-stale-after-seconds")
    long staleAfterSeconds;

    /**
     * The heartbeat interval, read so the pair can be checked rather than assumed.
     *
     * <p>The staleness threshold and the heartbeat interval are ONE decision made in two places, and
     * a threshold that does not exceed the interval reaps healthy runs by construction. Both this and
     * the scheduler that uses it resolve the same property from {@code application.yml}, with no
     * inline default on either — two literals for one decision is the very fault this check exists to
     * catch, reproduced inside the check.
     */
    @ConfigProperty(name = "spire.run.lease-heartbeat")
    Duration heartbeatEvery;

    void check(@Observes StartupEvent event) {
        verify(Duration.ofSeconds(staleAfterSeconds), heartbeatEvery);
        if (!enabled) {
            LOG.warn("the orphan watchdog is DISABLED; a sandbox that outlives its run will not be "
                    + "reclaimed and will keep its credentials until an operator removes it");
        }
    }

    /**
     * The rule, separable from CDI so a test can drive it with values that match nothing shipped.
     *
     * <p>The margin is deliberate rather than a strict inequality: a threshold one second above the
     * interval means a single delayed sweep condemns a live run.
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

    /**
     * One pass over the daemon's units.
     *
     * <p>The staleness horizon is read from the DATABASE once per tick, not from this JVM's clock.
     * {@code heartbeat_at} is written with the database's {@code now()}, so comparing it to a local
     * instant makes reclamation depend on a container's clock agreeing with a database host's — and
     * the failure is silent in the destroying direction. One read also gives the whole tick one
     * consistent horizon instead of a moving one.
     */
    @Scheduled(every = "${spire.run.orphan-sweep}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        if (!enabled) {
            return;
        }
        Optional<Instant> horizon = leases.staleBefore(Duration.ofSeconds(staleAfterSeconds));
        if (horizon.isEmpty()) {
            // The database could not answer, so nothing can be judged stale. Reclaiming nothing is a
            // leak an operator can still see; reclaiming on an unknown clock destroys running work.
            LOG.error("the staleness horizon could not be read; nothing is being reclaimed this tick");
            return;
        }
        for (RunHandle unit : units()) {
            MDC.put(RUN_ID_MDC, unit.runId());
            try {
                reapIfOrphaned(unit, horizon.orElseThrow());
            } catch (RuntimeException e) {
                // One unit that cannot be judged must not abandon the tick: every orphan behind it
                // would leak, and the next tick meets the same one first.
                LOG.errorf(e, "this unit could not be examined; leaving it for the next sweep");
            } finally {
                MDC.remove(RUN_ID_MDC);
            }
        }
    }

    private List<RunHandle> units() {
        try {
            return runtime.discoverUnits();
        } catch (RuntimeException e) {
            LOG.errorf(e, "the run units could not be listed; nothing is being reclaimed this tick");
            return List.of();
        }
    }

    private void reapIfOrphaned(RunHandle unit, Instant staleBefore) {
        if (registry.isExecuting(unit.runId())) {
            // This process is running it. No lease state can outrank that.
            return;
        }
        Optional<WorkspaceLeases.Lease> lease = leases.find(unit.runId());
        if (lease.isPresent() && !isOrphaned(lease.orElseThrow(), staleBefore)) {
            return;
        }
        boolean preserved = lease.map(WorkspaceLeases.Lease::preserved).orElse(false);
        LOG.warnf("reclaiming a sandbox with %s", preserved
                ? "a lease marked preserved" : lease.isPresent()
                ? "a lease nobody has heartbeated" : "no lease at all");
        reap(unit, preserved);
    }

    /**
     * Whether this lease means the sandbox may be reclaimed.
     *
     * <p>Preserved first, and not folded into staleness: a preserved unit's run is OVER, so waiting
     * for its heartbeat to age would leave a credential-bearing container up for the whole staleness
     * window after every timeout and every failed salvage.
     */
    private static boolean isOrphaned(WorkspaceLeases.Lease lease, Instant staleBefore) {
        return lease.preserved() || lease.heartbeatAt().isBefore(staleBefore);
    }

    /**
     * @param alreadyReported whether the run's own terminal result has already been published —
     *                        true for a unit the launcher deliberately preserved, which is over and
     *                        was reported by the dispatcher before the lease was stamped. Reporting
     *                        it again would contradict that result with a retryable failure the
     *                        launcher's own rules forbid, and land a second, unpriceable charge line
     *                        on a run whose spend is already recorded.
     */
    private void reap(RunHandle unit, boolean alreadyReported) {
        Finalization finalization = salvage(unit);
        if (!finalization.salvaged()) {
            // Decided on the VALUE, not on a throw. The shipped runtime reports "could not observe
            // the agent" as Finalization.faulted or .overran and raises nothing, so branching on the
            // exception left this rule inoperative exactly where it matters — and destroying here
            // removes the only remaining evidence of what the agent was doing.
            if (!alreadyReported) {
                report(unit, "its sandbox outlived the control plane and could NOT be read ("
                        + finalization.detail() + "); the unit is preserved and was not destroyed");
            }
            stop(unit);
            return;
        }
        if (!alreadyReported) {
            report(unit, "its sandbox outlived the control plane and was reclaimed (exit "
                    + finalization.exitCode() + ")");
        }
        if (destroy(unit)) {
            leases.release(unit.runId());
        }
    }

    private Finalization salvage(RunHandle unit) {
        try {
            // Salvage BEFORE destroy, the same rule the launcher keeps.
            return runtime.salvage(unit);
        } catch (RuntimeException e) {
            LOG.errorf(e, "its salvage threw during reclamation; the sandbox is kept");
            return Finalization.faulted(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Stop a unit that is being kept.
     *
     * <p>Kept is not the same as left running. That an unobservable agent has already exited is one
     * arm's private promise, not something the SPI states, and this is the caller most likely to be
     * looking at a container whose agent is still spending on a model nobody is watching.
     */
    private void stop(RunHandle unit) {
        try {
            runtime.cancel(unit);
        } catch (RuntimeException e) {
            LOG.errorf(e, "the preserved sandbox could not be stopped (%s)", e.getClass().getSimpleName());
        }
    }

    /** @return whether the sandbox is actually gone. False keeps the lease, so the next sweep retries. */
    private boolean destroy(RunHandle unit) {
        try {
            runtime.destroy(unit);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "its sandbox could not be destroyed during reclamation; the lease is kept "
                    + "so the next sweep tries again");
            return false;
        }
    }

    /**
     * Say what happened, with a cause, once ever.
     *
     * <p>{@code SANDBOX_LOST} rather than a bare failure: FR-F9's rule is that a failure with no
     * named cause reaches an operator as a row saying only that something went wrong, and this one
     * has a specific meaning — the run outlived whatever was supposed to be watching it. It is
     * retryable, because a replica eviction says nothing about the work.
     */
    private void report(RunHandle unit, String detail) {
        if (!claims.claim(unit.runId(), REAP_SLOT)) {
            LOG.debugf("its reclamation was already reported; retrying the teardown only");
            return;
        }
        boolean published = results.report(new RunResult.RunFailed(unit.runId(),
                RunFailureCause.SANDBOX_LOST.name(), RunFailures.clip(detail),
                RunFailureCause.SANDBOX_LOST.isRetryable(),
                // Unknown, and it stays unknown: nothing here measured what the agent spent, and a
                // zero would price a reclaimed run as free.
                null));
        if (!published) {
            // The claim is taken BEFORE the publish so a duplicate report cannot land a second
            // unpriceable charge line on a run whose spend is already recorded. That ordering is
            // right, and it means a broker outage during these few seconds would otherwise burn
            // the slot for ever: the sandbox is torn down next, the lease released, the unit never
            // listed again, and the run left open with no automated path to a terminal result.
            // Giving the slot back is safe precisely because nothing was published.
            claims.release(unit.runId(), REAP_SLOT);
        }
    }
}
