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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * lease, which is a sandbox holding a credential that nothing knows exists.
 *
 * <p><b>A preserved unit is STAMPED, not merely left alone.</b> Skipping the delete is not enough:
 * the heartbeat sweep would keep refreshing the row for the life of the replica, so it could never go
 * stale and a staleness-based watchdog would never see it. {@link #preserve} stops the heartbeat, so
 * the row ages, and {@link #staleLeases} reports it at once — there is nothing left to wait for.
 *
 * <p>Every clock is the DATABASE's, on the write and on the read alike, so staleness never depends on
 * one replica's clock agreeing with another's.
 */
@ApplicationScoped
public class WorkspaceLeases {

    private static final Logger LOG = Logger.getLogger(WorkspaceLeases.class);

    /**
     * How long any one lease statement may block.
     *
     * <p>The heartbeat runs under {@code ConcurrentExecution.SKIP}, so a single tick blocked on a
     * half-open connection silences every later tick — and after the watchdog's threshold every live
     * run on this replica reads as an orphan. A missed heartbeat is the reason a watchdog reaps real
     * work, so the statement that prevents it must not be the one that hangs.
     */
    private static final int STATEMENT_TIMEOUT_SECONDS = 5;

    /**
     * This process's identity, for the life of the process.
     *
     * <p>A fresh value per START rather than a stable host name, and that is the point: a replica
     * that restarts has lost every run it was executing, so the leases bearing its old id SHOULD read
     * as another owner's and become reapable. A stable identity would let a restarted process look
     * like the rightful owner of units it can no longer do anything with.
     */
    private final String ownerId = UUID.randomUUID().toString();

    /** Whether the last lease write failed, so an outage is reported once rather than every tick. */
    private final AtomicBoolean degraded = new AtomicBoolean();

    @Inject
    DataSource dataSource;

    /** This process's identity, as written into every lease it takes. */
    public String ownerId() {
        return ownerId;
    }

    /**
     * Take the lease for a run.
     *
     * <p><b>The one lease write that reports its failure</b>, because it is the only one that runs
     * BEFORE the unit exists. Everything else here happens after the money is spent, where throwing
     * would discard a terminal result to protect bookkeeping; here there is no result yet, and
     * failing quietly produces exactly the state the design forbids — a sandbox with no lease — for
     * the entire life of the run. Refusing costs one un-run command.
     *
     * <p>Idempotent on redelivery by taking the owner over, because the claim store — not this — is
     * the idempotency mechanism, and a lease that refused would leave a run executing with no
     * heartbeat and so look like an orphan to the watchdog while it was working. The unit id is
     * cleared on that path: a re-taken lease names a unit the previous attempt created, and keeping
     * it would point an operator at the wrong sandbox.
     *
     * @return false when the write failed, which the caller must treat as a refusal to run
     */
    public boolean take(String runId) {
        String sql = """
                INSERT INTO runworker.run_lease (run_id, owner_id, heartbeat_at)
                VALUES (?, ?, now())
                ON CONFLICT (run_id) DO UPDATE
                    SET owner_id = EXCLUDED.owner_id, heartbeat_at = now(),
                        unit_id = NULL, preserved_at = NULL
                """;
        return update(sql, "take the lease for", runId, statement -> {
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

    /**
     * Mark this run's unit as deliberately kept.
     *
     * <p>The lease stays — a preserved unit is exactly what the watchdog exists to find — but it
     * stops being heartbeated, so it ages instead of looking alive forever. Skipping the delete
     * without this was the defect a review caught: the sweep refreshed the row every thirty seconds
     * and the preservation was invisible again.
     */
    public void preserve(String runId) {
        update("UPDATE runworker.run_lease SET preserved_at = now() WHERE run_id = ?",
                "mark the preserved unit for", runId, statement -> statement.setString(1, runId));
    }

    /** Give up the lease. A run whose unit was destroyed no longer owns anything. */
    public void release(String runId) {
        update("DELETE FROM runworker.run_lease WHERE run_id = ?", "release the lease for", runId,
                statement -> statement.setString(1, runId));
    }

    /**
     * Advance the heartbeat on every lease this replica holds for a run still in flight.
     *
     * <p>One sweep rather than a thread per run: the launcher blocks for the whole duration of its
     * run, so a per-run heartbeat would need a thread that exists only to say "still here", and a
     * hundred concurrent runs would be a hundred of them.
     *
     * <p>{@code preserved_at IS NULL} is load-bearing, not a tidy-up. Without it a preserved unit's
     * lease is refreshed for the life of the replica and can never go stale, so a watchdog defined on
     * staleness never finds it — the preservation being invisible is the exact condition this whole
     * mechanism exists to remove.
     *
     * <p>The interval is deliberately far shorter than any staleness threshold a watchdog should
     * use. A live hour-long run whose heartbeat lapses reads as an orphan, and the answer to an
     * orphan is to destroy it — so the failure mode of a heartbeat that is too slow is killing real
     * work, which is why this is the cheap operation and the sweep is frequent.
     */
    @Scheduled(every = "${spire.run.lease-heartbeat:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void heartbeat() {
        update("""
                UPDATE runworker.run_lease SET heartbeat_at = now()
                 WHERE owner_id = ? AND preserved_at IS NULL
                """, "heartbeat the leases of", ownerId, statement -> statement.setString(1, ownerId));
    }

    /**
     * The lease on one run.
     *
     * <p>Throws on a read fault rather than answering empty, and the asymmetry with
     * {@link #staleLeases} is deliberate. "No lease" is the watchdog's licence to REAP, so an empty
     * answer produced by a database blip would destroy every live run on the daemon. A caller that
     * cannot read the lease must skip its tick, not act on the silence.
     */
    public Optional<Lease> find(String runId) {
        String sql = """
                SELECT run_id, owner_id, unit_id, heartbeat_at, preserved_at
                  FROM runworker.run_lease WHERE run_id = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            statement.setString(1, runId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the lease for " + runId
                    + "; a caller must skip rather than treat this as 'no lease'", e);
        }
    }

    /**
     * Every lease that is preserved, or whose heartbeat is older than {@code staleAfter}.
     *
     * <p>Not filtered by owner. A lease this replica holds and has stopped heartbeating is exactly
     * as stale as a dead replica's, and the whole reason for an owner column is to say WHOSE it was
     * afterwards rather than to exclude anyone from the scan.
     *
     * <p>Empty on a read fault, which fails CLOSED for a watchdog: reaping nothing is a leak an
     * operator can still see, while reaping on a database blip destroys running work. The opposite
     * choice from {@link #find}, for the same reason — each answers in the direction that does not
     * destroy anything.
     *
     * @throws IllegalArgumentException on a non-positive window, which would otherwise put the
     *                                  threshold at or after now and report EVERY lease as stale
     */
    public List<Lease> staleLeases(Duration staleAfter) {
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("a staleness window must be positive; " + staleAfter
                    + " would report every lease as an orphan, including runs that started a moment ago");
        }
        String sql = """
                SELECT run_id, owner_id, unit_id, heartbeat_at, preserved_at
                  FROM runworker.run_lease
                 WHERE preserved_at IS NOT NULL
                    OR heartbeat_at < now() - make_interval(secs => ?)
                 ORDER BY heartbeat_at
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            // Bound as a NUMBER of seconds rather than composed into an interval string, so a
            // sub-second window cannot truncate to "0 seconds" and match every row — the fail-closed
            // posture above flipping to reap-everything.
            statement.setDouble(1, staleAfter.toMillis() / 1000.0);
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
        Timestamp preserved = rs.getTimestamp("preserved_at");
        return new Lease(rs.getString("run_id"), rs.getString("owner_id"), rs.getString("unit_id"),
                rs.getTimestamp("heartbeat_at").toInstant(),
                preserved == null ? null : preserved.toInstant());
    }

    /**
     * Run one statement, reporting rather than throwing.
     *
     * <p>A lease is bookkeeping ABOUT a run, not part of it. Throwing from here would turn a
     * database blip into a lost terminal result — the run's outcome discarded to protect a row whose
     * only purpose is to help find the sandbox afterwards.
     *
     * <p>Reported ONCE per outage rather than per call. The heartbeat fires every thirty seconds, so
     * an unreported recovery and a stack trace on every tick would bury the one line that matters
     * under a hundred an hour saying the same thing.
     *
     * @return whether the statement ran, for the one caller that must refuse rather than continue
     */
    private boolean update(String sql, String what, String subject, Bind bind) {
        try (Connection c = dataSource.getConnection(); PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
            bind.apply(statement);
            statement.executeUpdate();
            if (degraded.compareAndSet(true, false)) {
                LOG.info("lease writes are working again; heartbeats are being recorded");
            }
            return true;
        } catch (SQLException e) {
            if (degraded.compareAndSet(false, true)) {
                LOG.errorf(e, "could not %s %s; while this lasts a live run's unit may be reclaimed"
                        + " early or a finished one may leak", what, subject);
            }
            return false;
        }
    }

    @FunctionalInterface
    private interface Bind {
        void apply(PreparedStatement statement) throws SQLException;
    }

    /**
     * One replica's claim on one run unit.
     *
     * @param unitId      null until the unit exists — the window the take-before-create ordering
     *                    creates on purpose
     * @param preservedAt non-null once the unit was deliberately kept, which stops the heartbeat and
     *                    makes the row immediately actionable to a watchdog
     */
    public record Lease(String runId, String ownerId, String unitId, Instant heartbeatAt, Instant preservedAt) {

        /** Whether this lease names a unit that was deliberately kept rather than destroyed. */
        public boolean preserved() {
            return preservedAt != null;
        }
    }
}
