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

    /** Runs already reported as unrecordable, so a permanent fault is logged once, not per event. */
    private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Records one event, ignoring a redelivery of the same one.
     *
     * <p>The producer does not await the broker — a transcript is a convenience and awaiting each
     * event would put broker latency inside the agent's log-reading loop — so at-least-once is the
     * delivery this side sees. The key is the run and its place in the stream, so a redelivery
     * conflicts rather than appending a second copy, and no reader has to de-duplicate.
     */
    public boolean record(RunEventRecord event) {
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
            // Zero means the conflict clause absorbed a redelivery, which is success, not loss.
            return ps.executeUpdate() > 0;
        } catch (RuntimeException | SQLException e) {
            // RuntimeException as well as SQLException: encryption throws one on a key fault, and
            // before this it escaped here, escaped the consumer, and stopped the channel — taking
            // the whole service out of rotation over one transcript line.
            //
            // Never rethrown. Failing would stop a channel that deliberately has no dead-letter
            // route, so the record could never be redeemed. A transcript line is a convenience; a
            // run result is not, and they do not share a failure policy.
            reportOnce(event.runId(), e);
            return false;
        }
    }

    /**
     * One report per run, with the throwable.
     *
     * <p>Logging only the exception's class name made a permanent fault — a bad keyset, schema
     * drift, an oversized payload — indistinguishable from a transient blip, and repeated it for
     * every event of the run. The cause is what an operator needs, and they need it once.
     */
    private void reportOnce(String runId, Exception cause) {
        if (reported.add(runId)) {
            LOG.warnf(cause, "run %s: its transcript is not being recorded; the tail will have gaps", runId);
        }
    }

    /**
     * The run's NEWEST events, returned oldest-first so a reader can render them in order.
     *
     * <p>Newest rather than oldest, which the first version got backwards while three comments
     * claimed otherwise. A run may produce ten thousand events; a page of the first two hundred
     * shows the agent starting up, and the end — where a failure is — is unreachable.
     */
    public List<RunEventRecord> newestPage(String runId, int limit) {
        return read(runId, "SELECT * FROM (SELECT seq, at, kind, is_error, payload FROM run_event"
                + " WHERE run_id = ? ORDER BY seq DESC LIMIT ?) newest ORDER BY seq", runId, limit);
    }

    /** Everything after {@code afterSeq}, so a reader can page forward through a long run. */
    public List<RunEventRecord> since(String runId, long afterSeq, int limit) {
        return read(runId, "SELECT seq, at, kind, is_error, payload FROM run_event"
                + " WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT ?", runId, afterSeq, limit);
    }


    private List<RunEventRecord> read(String runId, String sql, Object... binds) {
        List<RunEventRecord> events = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setObject(i + 1, binds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(new RunEventRecord(runId, rs.getLong("seq"),
                            rs.getTimestamp("at").toInstant(), rs.getString("kind"),
                            textOf(rs.getString("payload"), runId), rs.getBoolean("is_error")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the transcript of " + runId, e);
        }
        return events;
    }

    /**
     * One unreadable row costs that row, not the page.
     *
     * <p>A rotated key or a single corrupt payload used to take out the whole transcript and the
     * socket's snapshot with it, because the only catch here was for SQL. Every other decrypt site
     * in this service already guards the same way.
     */
    private String textOf(String stored, String runId) {
        try {
            return encryption.decryptString(stored, aad(runId));
        } catch (RuntimeException unreadable) {
            return "[this line could not be decrypted]";
        }
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
