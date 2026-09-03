package dev.codespire.runworker;

import org.jboss.logging.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The run worker's only idempotency mechanism.
 *
 * <p>The command channel acks on RECEIPT, because an hour-long run cannot ride an
 * ordered-blocking channel — that pairing once stalled a consumer which then re-stalled on every
 * restart and needed a manual offset seek. So a redelivery is not stopped by Kafka; it is stopped
 * here, and the write order matters: claim FIRST, then ack.
 */
@ApplicationScoped
public class RunClaimStore {

    private static final Logger LOG = Logger.getLogger(RunClaimStore.class);

    @Inject
    DataSource dataSource;

    /** @return true when THIS caller took the slot; false when it was already taken. */
    /**
     * Gives a slot back, for the one shape that needs it: a claim taken BEFORE work that then
     * failed in a way that leaves nothing recorded.
     *
     * <p>Deliberately not a general undo. A claim exists so a paid or duplicate action happens
     * once, and releasing one after the action succeeded would re-arm exactly that. The caller
     * must have established that the work did NOT happen — the watchdog's report is the case: it
     * claims, then publishes, and a broker outage during that publish would otherwise burn the
     * slot for ever and leave the run with no path to a terminal result.
     *
     * <p>Failure to release is logged rather than thrown: the caller is already handling a fault,
     * and replacing its error with this one loses the fault that mattered. The cost is a slot that
     * stays taken, which is the state it was in anyway.
     */
    public void release(String runId, String slot) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM runworker.run_claim WHERE run_id = ? AND slot = ?")) {
            ps.setString(1, runId);
            ps.setString(2, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "could not release the %s claim for %s; it stays taken, so the action it "
                    + "guards will not be retried automatically", slot, runId);
        }
    }

    /**
     * Whether a slot is already taken, WITHOUT taking it.
     *
     * <p>Reading a claim rather than competing for one is a different question from
     * {@link #claim}: it asks "has something already happened", not "may I be the one to do it".
     * The cancel slot is the case — the dispatcher must not consume it, because a cancel
     * recorded for a run that is redelivered must still stop the second delivery.
     *
     * <p><b>Fails CLOSED, in the direction that spends no money.</b> An unreadable claim table
     * throws, exactly as {@link #claim} does, and the dispatcher turns that into a terminal
     * failure the operator can retry. Answering "not cancelled" on a database fault would run an
     * agent an operator had explicitly stopped, which is the more expensive of the two mistakes
     * and the harder to notice.
     */
    public boolean taken(String runId, String slot) {
        String sql = "SELECT 1 FROM runworker.run_claim WHERE run_id = ? AND slot = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, slot);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the " + slot + " claim for " + runId, e);
        }
    }

    public boolean claim(String runId, String slot) {
        String sql = """
                INSERT INTO runworker.run_claim (run_id, slot)
                VALUES (?, ?)
                ON CONFLICT (run_id, slot) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, slot);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            // Fail CLOSED. An unreadable claim table must not authorise a paid run: answering
            // "true" on a database fault turns one outage into an unbounded number of duplicate
            // agent runs, which is the shape the LLM idempotency claim already learned from.
            throw new IllegalStateException("could not take the run claim for " + runId, e);
        }
    }
}
