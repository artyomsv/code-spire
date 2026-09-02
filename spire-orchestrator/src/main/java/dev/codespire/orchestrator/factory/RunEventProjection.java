package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.encryption.EncryptionService;
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

/**
 * The run transcript: bounded, TTL'd, and encrypted where it may quote source (FR-F5, ADR-034).
 *
 * <p>The second event tier. Nothing derives state from these rows and they are not replayable —
 * they exist for the live tail, for debugging, and for a transcript an operator can read. That is
 * why they live in their own table rather than in the event store, whose volume they would multiply
 * by three orders of magnitude.
 *
 * <p><b>The payload is encrypted and the queryable columns are not.</b> A tool result quotes source
 * and a thinking line quotes whatever the agent was reading (ADR-011), while the run id, the
 * sequence, the kind and the error flag are what any query needs. The same split
 * {@code review_finding} already makes between its location columns and its message.
 */
@ApplicationScoped
public class RunEventProjection {

    private static final Logger LOG = Logger.getLogger(RunEventProjection.class);

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    /**
     * Records one event, ignoring a redelivery of the same one.
     *
     * <p>The producer does not await the broker — a transcript is a convenience and awaiting each
     * event would put broker latency inside the agent's log-reading loop — so at-least-once is the
     * delivery this side sees. The key is the run and its place in the stream, so a redelivery
     * conflicts rather than appending a second copy, and no reader has to de-duplicate.
     */
    public void record(RunEventRecord event) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO run_event (run_id, seq, at, kind, is_error, payload)"
                             + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (run_id, seq) DO NOTHING")) {
            ps.setString(1, event.runId());
            ps.setLong(2, event.sequence());
            ps.setTimestamp(3, Timestamp.from(event.at()));
            ps.setString(4, event.kind());
            ps.setBoolean(5, event.error());
            ps.setString(6, encryption.encryptString(event.text(), aad(event.runId())));
            ps.executeUpdate();
        } catch (SQLException e) {
            // Never rethrown. A transcript line that cannot be stored is a gap in a convenience;
            // failing here would dead-letter an event on a topic that is deliberately not
            // replayable, and would put an unbounded TTL'd record into a queue meant for things
            // that must not be lost.
            LOG.warnf("run %s: event %d was not recorded (%s); the transcript will have a gap",
                    event.runId(), event.sequence(), e.getClass().getSimpleName());
        }
    }

    /** One run's events in stream order, at most {@code limit} of them. */
    public List<RunEventRecord> transcript(String runId, int limit) {
        List<RunEventRecord> events = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq, at, kind, is_error, payload FROM run_event"
                             + " WHERE run_id = ? ORDER BY seq LIMIT ?")) {
            ps.setString(1, runId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new RunEventRecord(runId, rs.getLong("seq"),
                            rs.getTimestamp("at").toInstant(), rs.getString("kind"),
                            encryption.decryptString(rs.getString("payload"), aad(runId)),
                            rs.getBoolean("is_error")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the transcript of " + runId, e);
        }
        return events;
    }

    /**
     * Deletes everything recorded before the window, and returns how much went.
     *
     * <p>The retention half of the bound. The worker caps events per run before they are sent, which
     * stops one agent flooding the bus; this stops a busy deployment growing the table without
     * limit. Neither subsumes the other — the first is about one run, the second about all of them
     * over time.
     */
    public int sweep(Duration keepFor) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM run_event WHERE recorded_at < ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(keepFor)));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.infof("swept %d run event(s) older than %s", deleted, keepFor);
            }
            return deleted;
        } catch (SQLException e) {
            // A sweep that cannot run is a table that grows, not a run that fails. Reported and
            // retried on the next tick rather than propagated into whatever triggered it.
            LOG.errorf(e, "the run-event sweep failed; the transcript table is not being trimmed");
            return 0;
        }
    }

    /** Binds a row's ciphertext to the run it belongs to, so it cannot be replayed under another. */
    private static String aad(String runId) {
        return "run-event:" + runId;
    }
}
