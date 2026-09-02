package dev.codespire.runworker;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which run unit this replica currently owns, and the heartbeat that defines an orphan.
 *
 * <p>{@code runworker.run_lease} was created in V1 with a comment explaining why owner plus
 * heartbeat is the pair that matters — without them nothing can tell a dead replica's leak from a
 * live replica's healthy hour-long run, and getting it wrong either kills real work or leaks forever.
 * The table was then written and read by nothing. This is that description made real.
 *
 * <p><b>The lease is taken BEFORE the unit is created</b>, so a crash between the two leaves a lease
 * with no unit — a row the watchdog can reconcile against the daemon — and never a unit with no
 * lease, which is a sandbox holding a credential that nothing knows exists. The unit id is recorded
 * as soon as it exists, which is also the moment {@code RunStarted} can finally carry something an
 * operator can look up.
 *
 * <p><b>A preserved unit keeps its lease, deliberately.</b> Releasing it would make the preservation
 * invisible again, and a preserved unit is precisely what the orphan watchdog exists to find.
 */
@ApplicationScoped
public class WorkspaceLeases {

    private static final Logger LOG = Logger.getLogger(WorkspaceLeases.class);

    /**
     * This process's identity, for the life of the process.
     *
     * <p>A fresh value per START rather than a stable host name, and that is the point: a replica
     * that restarts has lost every run it was executing, so the leases bearing its old id SHOULD read
     * as another owner's and become reapable. A stable identity would let a restarted process look
     * like the rightful owner of units it can no longer do anything with.
     */
    private final String ownerId = UUID.randomUUID().toString();

    @Inject
    DataSource dataSource;

    /** This process's identity, as written into every lease it takes. */
    public String ownerId() {
        return ownerId;
    }

    /**
     * Take the lease for a run.
     *
     * <p>Idempotent on redelivery by taking the owner over, because the claim store — not this — is
     * the idempotency mechanism, and a lease that refused would leave a run executing with no
     * heartbeat and so look like an orphan to the watchdog while it was working.
     */
    public void take(String runId) {
        String sql = """
                INSERT INTO runworker.run_lease (run_id, owner_id, heartbeat_at)
                VALUES (?, ?, now())
                ON CONFLICT (run_id) DO UPDATE SET owner_id = EXCLUDED.owner_id, heartbeat_at = now()
                """;
        update(sql, "take the lease for", runId, statement -> {
            statement.setString(1, runId);
            statement.setString(2, ownerId);
        });
    }

    /**
     * Record which sandbox this lease is a lease on.
     *
     * <p>Written when the unit exists, not before. The watchdog can find a sandbox by its label
     * without this, but only this says which run the control plane BELIEVES owns it — which is what
     * turns "there is a container here" into "this run's container is still up".
     */
    public void recordUnit(String runId, String unitId) {
        update("UPDATE runworker.run_lease SET unit_id = ? WHERE run_id = ?",
                "record the unit for", runId, statement -> {
                    statement.setString(1, unitId);
                    statement.setString(2, runId);
                });
    }

    /** Give up the lease. A run whose unit was destroyed no longer owns anything. */
    public void release(String runId) {
        update("DELETE FROM runworker.run_lease WHERE run_id = ?", "release the lease for", runId,
                statement -> statement.setString(1, runId));
    }

    /**
     * Advance the heartbeat on every lease this replica holds.
     *
     * <p>One sweep rather than a thread per run: the launcher blocks for the whole duration of its
     * run, so a per-run heartbeat would need a thread that exists only to say "still here", and a
     * hundred concurrent runs would be a hundred of them.
     *
     * <p>The interval is deliberately far shorter than any staleness threshold a watchdog should
     * use. A live hour-long run whose heartbeat lapses reads as an orphan, and the watchdog's answer
     * to an orphan is to destroy it — so the failure mode of a heartbeat that is too slow is killing
     * real work, which is why this is the cheap operation and the sweep is frequent.
     */
    @Scheduled(every = "${spire.run.lease-heartbeat:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void heartbeat() {
        update("UPDATE runworker.run_lease SET heartbeat_at = now() WHERE owner_id = ?",
                "heartbeat the leases of", ownerId, statement -> statement.setString(1, ownerId));
    }

    /** The lease on one run, or empty when this replica does not hold one. */
    public Optional<Lease> find(String runId) {
        String sql = "SELECT run_id, owner_id, unit_id, heartbeat_at FROM runworker.run_lease WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            LOG.errorf(e, "run %s: its lease could not be read", runId);
            return Optional.empty();
        }
    }

    /**
     * Every lease whose heartbeat is older than {@code staleAfter}.
     *
     * <p>Not filtered by owner. A lease this replica holds and has stopped heartbeating is exactly
     * as stale as a dead replica's, and the whole reason for an owner column is to say WHOSE it was
     * afterwards rather than to exclude anyone from the scan.
     *
     * <p>Empty on a read fault, which fails CLOSED for a watchdog: reaping nothing is a leak an
     * operator can still see, while reaping on a database blip destroys running work.
     */
    public List<Lease> staleLeases(Duration staleAfter) {
        String sql = """
                SELECT run_id, owner_id, unit_id, heartbeat_at
                  FROM runworker.run_lease
                 WHERE heartbeat_at < now() - CAST(? AS INTERVAL)
                 ORDER BY heartbeat_at
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, staleAfter.toSeconds() + " seconds");
            try (ResultSet rs = statement.executeQuery()) {
                List<Lease> leases = new ArrayList<>();
                while (rs.next()) {
                    leases.add(read(rs));
                }
                return leases;
            }
        } catch (SQLException e) {
            LOG.errorf(e, "the stale-lease scan failed; nothing is being reclaimed this tick");
            return List.of();
        }
    }

    private static Lease read(ResultSet rs) throws SQLException {
        return new Lease(rs.getString("run_id"), rs.getString("owner_id"),
                rs.getString("unit_id"), rs.getTimestamp("heartbeat_at").toInstant());
    }

    /**
     * Run one statement, reporting rather than throwing.
     *
     * <p>A lease is bookkeeping ABOUT a run, not part of it. Throwing from here would turn a
     * database blip into a lost terminal result — the run's outcome discarded to protect a row whose
     * only purpose is to help find the sandbox afterwards. The cost of the swallow is bounded and
     * named: a missed heartbeat is why the watchdog may reap a live unit, so it is said out loud.
     */
    private void update(String sql, String what, String subject, Bind bind) {
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            bind.apply(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf(e, "could not %s %s; its unit may be reclaimed early or leak", what, subject);
        }
    }

    @FunctionalInterface
    private interface Bind {
        void apply(PreparedStatement statement) throws SQLException;
    }

    /**
     * One replica's claim on one run unit.
     *
     * @param unitId null until the unit exists — the window the take-before-create ordering creates
     *               on purpose
     */
    public record Lease(String runId, String ownerId, String unitId, Instant heartbeatAt) {
    }
}
