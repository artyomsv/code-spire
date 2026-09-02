package dev.codespire.runworker;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lease that defines an orphan.
 *
 * <p>V1 created this table with a comment explaining why owner plus heartbeat is the pair that
 * matters, and then nothing wrote or read it: {@code discoverOrphans} exists and is called from no
 * production code, and a replica evicted mid-run leaves its containers behind holding a live model
 * credential with no terminal result ever emitted. These assert the half that finally exists.
 */
@QuarkusTest
class WorkspaceLeasesIT {

    @Inject
    WorkspaceLeases leases;

    @Inject
    DataSource dataSource;

    private static String runId() {
        return "run::github:TEST-acme/app:lease-" + UUID.randomUUID() + ":1";
    }

    @Test
    void aLeaseIsTakenBeforeTheUnitExists() {
        // The ordering guarantee, stated as a value rather than as a comment. The lease is taken
        // before runtime.create, so there is a real window in which it names no unit — and that
        // window is deliberate: a crash inside it leaves a row the watchdog can reconcile against
        // the daemon, where the reverse ordering would leave a sandbox holding a credential that
        // nothing knows exists.
        String runId = runId();

        leases.take(runId);

        WorkspaceLeases.Lease lease = leases.find(runId).orElseThrow();
        assertNull(lease.unitId(), "a lease with no unit yet is the normal state, not a fault");
        assertEquals(leases.ownerId(), lease.ownerId());
    }

    @Test
    void theUnitIsRecordedOnceItExists() {
        // Without this the watchdog can find a sandbox by its label but nothing says which run the
        // control plane believes owns it — the difference between "there is a container here" and
        // "this run's container is still up".
        String runId = runId();
        leases.take(runId);

        leases.recordUnit(runId, "container-abc123");

        assertEquals("container-abc123", leases.find(runId).orElseThrow().unitId());
    }

    @Test
    void theHeartbeatAdvancesWhileTheRunIsStillGoing() {
        // A live hour-long run whose heartbeat does not move reads as an orphan, and the watchdog's
        // answer to an orphan is to destroy it. So the failure mode of a heartbeat that does not
        // advance is killing real work, which is why this is asserted rather than assumed.
        String runId = runId();
        leases.take(runId);
        backdateHeartbeat(runId, Duration.ofMinutes(10));
        var before = leases.find(runId).orElseThrow().heartbeatAt();

        leases.heartbeat();

        assertTrue(leases.find(runId).orElseThrow().heartbeatAt().isAfter(before),
                "the run has not finished, so its lease must not look abandoned");
    }

    @Test
    void aReleasedLeaseIsGone() {
        String runId = runId();
        leases.take(runId);

        leases.release(runId);

        assertEquals(Optional.empty(), leases.find(runId));
    }

    @Test
    void aStaleLeaseIsFoundAndAFreshOneIsNot() {
        // The whole definition of an orphan, in one assertion. Reap eagerly and the watchdog kills
        // real work; reap lazily and an eviction leaks forever — so the boundary is the thing.
        String stale = runId();
        String fresh = runId();
        leases.take(stale);
        leases.take(fresh);
        backdateHeartbeat(stale, Duration.ofMinutes(30));

        List<String> found = leases.staleLeases(Duration.ofMinutes(5)).stream()
                .map(WorkspaceLeases.Lease::runId)
                .toList();

        assertTrue(found.contains(stale), "a lease nobody has heartbeated in half an hour is an orphan");
        assertTrue(!found.contains(fresh), "and a live run's lease is not, however long the run takes");
    }

    @Test
    void theStaleScanIsNotFilteredByOwner() {
        // A lease THIS replica holds and has stopped heartbeating is exactly as stale as a dead
        // replica's. The owner column exists to say whose it was afterwards, not to exclude anyone
        // from the scan — filtering by it would make a hung replica invisible to its own watchdog.
        String runId = runId();
        leases.take(runId);
        backdateHeartbeat(runId, Duration.ofMinutes(30));

        assertTrue(leases.staleLeases(Duration.ofMinutes(5)).stream()
                        .anyMatch(lease -> lease.runId().equals(runId) && lease.ownerId().equals(leases.ownerId())),
                "this replica's own abandoned lease must be findable, and must still name this replica");
    }

    @Test
    void aRestartedReplicaIsADifferentOwner() {
        // A fresh identity per process, deliberately. A replica that restarts has lost every run it
        // was executing, so leases bearing its old id SHOULD read as another owner's and become
        // reapable. A stable host name would let a restarted process look like the rightful owner of
        // units it can no longer do anything with.
        WorkspaceLeases restarted = new WorkspaceLeases();

        assertNotEquals(leases.ownerId(), restarted.ownerId());
    }

    @Test
    void takingALeaseTwiceIsNotAFailure() {
        // The claim store — not this — is the idempotency mechanism. A lease that refused a second
        // take would leave a run executing with no heartbeat, so it would look like an orphan to the
        // watchdog exactly while it was working.
        String runId = runId();
        leases.take(runId);
        leases.recordUnit(runId, "container-abc123");

        leases.take(runId);

        assertEquals(leases.ownerId(), leases.find(runId).orElseThrow().ownerId());
    }

    /** Push a lease's heartbeat into the past, which no production path does. */
    private void backdateHeartbeat(String runId, Duration by) {
        String sql = "UPDATE runworker.run_lease SET heartbeat_at = now() - CAST(? AS INTERVAL) WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, by.toSeconds() + " seconds");
            statement.setString(2, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not backdate the lease for " + runId, e);
        }
    }
}
