package dev.codespire.runworker;

import io.quarkus.scheduler.Scheduled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every timer in this module has to be declared, and nothing else says so.
 *
 * <p>Every other test calls {@link WorkspaceLeases#heartbeat()} directly, so deleting the
 * {@code @Scheduled} annotation — or the {@code quarkus-scheduler} dependency it needs — leaves the
 * whole suite green while no lease is ever heartbeated again. A review proved exactly that.
 *
 * <p>The consequence of the silent version is the worst one available here: with no heartbeat every
 * live run's lease ages past the watchdog's threshold, and the watchdog's answer to a stale lease is
 * to destroy the sandbox. So the failure mode of the untested wiring is killing running work.
 *
 * <p>Reflection rather than a running scheduler, on purpose. The test profile disables the scheduler
 * so that a sweep nobody called cannot make "the heartbeat advanced" true for the wrong reason; this
 * asserts the DECLARATION instead, which is the half that can be deleted by accident.
 */
class ScheduledWorkIsDeclaredTest {

    @Test
    void theOrphanSweepRunsOnATimer() {
        // Measured, not assumed: deleting this annotation left all 17 suites of this module green
        // while the watchdog never ran, so the credential-bearing-container leak the sweep exists
        // to close came silently back. No boot test can catch it either, because the test profile
        // disables the scheduler — which is why the declaration is what gets asserted.
        Scheduled scheduled = annotationOn(OrphanWatchdog.class, "sweep");

        assertNotNull(scheduled, "without this nothing ever reclaims an abandoned sandbox");
        assertTrue(scheduled.every().contains("spire.run.orphan-sweep"),
                "the interval must be the operator's, and it is the value .env.example documents");
        assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution(),
                "overlapping sweeps would pile onto whatever the first one is stuck on");
    }

    /** The annotation on one method, or null. */
    private static Scheduled annotationOn(Class<?> type, String method) {
        try {
            return type.getMethod(method).getAnnotation(Scheduled.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(type.getSimpleName() + "." + method + "() is gone", e);
        }
    }

    @Test
    void theHeartbeatRunsOnATimer() throws Exception {
        Method heartbeat = WorkspaceLeases.class.getMethod("heartbeat");

        Scheduled scheduled = heartbeat.getAnnotation(Scheduled.class);

        assertNotNull(scheduled, "without this the lease is written once and never refreshed, so every"
                + " live run ages into a watchdog's definition of an orphan");
        assertTrue(scheduled.every().contains("spire.run.lease-heartbeat"),
                "the interval must be the operator's, and it is the value .env.example documents");
        assertFalse(scheduled.every().contains(":"),
                "and carries no inline default: the value is declared once in application.yml, so a"
                        + " change moves both the scheduler and the startup guard that checks it --"
                        + " two literals for one decision is the fault that guard exists to catch");
    }

    @Test
    void anOverrunningSweepDoesNotStackUp() {
        // Overlapping heartbeats would only pile more work onto whatever the first one is stuck on,
        // and each holds a connection. SKIP is right; it is also why every lease statement carries a
        // query timeout, since under SKIP one hung tick silences every later one.
        Scheduled scheduled = heartbeatAnnotation();

        assertEquals(Scheduled.ConcurrentExecution.SKIP, scheduled.concurrentExecution());
    }

    private static Scheduled heartbeatAnnotation() {
        try {
            return WorkspaceLeases.class.getMethod("heartbeat").getAnnotation(Scheduled.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("WorkspaceLeases.heartbeat() is gone", e);
        }
    }
}
