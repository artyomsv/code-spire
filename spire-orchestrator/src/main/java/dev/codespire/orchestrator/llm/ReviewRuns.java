package dev.codespire.orchestrator.llm;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Which run of a review is in flight — the discriminator {@link CallRefs#reviewSlot} folds into a
 * charge identity, because a re-run is by construction a second paid call on an UNCHANGED
 * {@code (reviewId, commit)}.
 *
 * <p>Counted as the number of {@code ReviewRequested} events in the review's own stream, which is
 * exactly the set of paths that make a second call possible, and nothing else:
 *
 * <ul>
 *   <li>A <b>re-run</b> (the Re-run button, or a {@code /review} PR comment) appends one and clears
 *       the worker's idempotency claim, so the LLM really runs again — the run number must move, or
 *       the second call's charges collide with the first's and are dropped.
 *   <li>A <b>new commit</b> appends one too. The commit in the slot already separates those, so the
 *       number only adds to an identity that was already distinct.
 *   <li>The <b>ADR-016 auto-retry</b> appends none: it re-dispatches from FetchDiff without a new
 *       request, and deliberately does NOT clear the worker claims, so the worker re-emits its
 *       persisted result. There is no second spend, and the run number must therefore NOT move —
 *       which is why this counts requests rather than attempts. {@code review_status.attempt} is the
 *       retry counter and would double-charge every auto-retry if reused here.
 * </ul>
 *
 * <p>Deliberately derived rather than stored: a persisted counter would need a column, and the count
 * is already recorded in the append-only log, so the two cannot drift apart. This was written when a
 * hard delete cleared {@code event_log} and {@code llm_charge} together; archiving now clears neither,
 * which strengthens the property rather than weakening it — an archived review keeps both halves.
 */
@ApplicationScoped
public class ReviewRuns {

    /** A review that has never been re-run. Its charges keep the bare commit as their slot. */
    public static final int FIRST_RUN = 1;

    /** No round could be determined. A round-keyed write must skip rather than guess. */
    public static final int UNKNOWN_RUN = -1;

    private static final Logger LOG = Logger.getLogger(ReviewRuns.class);

    /**
     * Bound as a value, not written into the SQL: {@code event_log.event_type} is whatever
     * {@code EventEnvelope.domain} derived from the payload class, so taking the name FROM that class
     * is what keeps the query and the writer from drifting apart.
     */
    private static final String REQUEST_EVENT =
            dev.codespire.contract.event.DomainEvent.ReviewRequested.class.getSimpleName();

    @Inject
    DataSource dataSource;

    /**
     * The current run's number, counting from {@link #FIRST_RUN}.
     *
     * <p>A read failure answers {@code FIRST_RUN} rather than throwing. Throwing would dead-letter the
     * whole result event — findings, verdicts and PostComments with it — for a figure only the ledger
     * needs, and the ledger write that follows shares this connection pool, so it will report the
     * outage itself. A CONSTANT fallback is the safe direction: it can only merge a re-run's charges
     * into the previous run's ref (the pre-existing behaviour), where a guessed or random one would
     * double-charge an ordinary redelivery.
     */
    /**
     * The run number, or {@link #UNKNOWN_RUN} when it could not be read.
     *
     * <p>{@link #currentRun} answers {@code FIRST_RUN} on a read failure, which is the safe
     * direction for the LEDGER -- a charge filed under run 1 is wrong but harmless, and refusing to
     * charge would lose the money entirely. It is the wrong direction for anything that WRITES a
     * round: a transient fault during round 5 would return 1, and a caller that replaces round 1's
     * rows would destroy real history and file round 5's findings under it.
     *
     * <p>So a caller whose write is keyed by round asks for this instead and skips on UNKNOWN.
     * Losing a round from a read model is recoverable; corrupting an earlier one is not.
     */
    public int roundOrUnknown(String reviewId) {
        String sql = "SELECT count(*) FROM event_log WHERE stream_id = ? AND event_type = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, REQUEST_EVENT);
            try (ResultSet rs = ps.executeQuery()) {
                int requests = rs.next() ? rs.getInt(1) : 0;
                return Math.max(FIRST_RUN, requests);
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Could not read the run number for %s — skipping the round-keyed write "
                    + "rather than filing it under run %d", reviewId, FIRST_RUN);
            return UNKNOWN_RUN;
        }
    }

    public int currentRun(String reviewId) {
        String sql = "SELECT count(*) FROM event_log WHERE stream_id = ? AND event_type = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, REQUEST_EVENT);
            try (ResultSet rs = ps.executeQuery()) {
                int requests = rs.next() ? rs.getInt(1) : 0;
                return Math.max(FIRST_RUN, requests);
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Could not read the run number for %s — charging it as run %d",
                    reviewId, FIRST_RUN);
            return FIRST_RUN;
        }
    }
}
