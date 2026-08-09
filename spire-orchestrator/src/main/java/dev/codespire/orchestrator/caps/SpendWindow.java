package dev.codespire.orchestrator.caps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * What the deployment has already spent, as the spend gates see it: one read of the {@code llm_charge}
 * ledger over a rolling window.
 *
 * <p>The window is rolling rather than a fixed bucket because a fixed bucket's only advantage is
 * predictability and it is a false one — with charge timestamps available the exact instant capacity
 * returns is computable, while the bucket keeps its boundary-gaming weakness (twice the limit across
 * two adjacent buckets) and buys nothing.
 */
@ApplicationScoped
public class SpendWindow {

    private static final Logger LOG = Logger.getLogger(SpendWindow.class);

    /**
     * Usage on both axes. Money is millicents, as everywhere else; {@code calls} counts distinct
     * {@code call_ref}s, so a review, its reconcile and each follow-up count separately.
     */
    public record Usage(long spentMillicents, int calls) {

        /** What a window with no charges — or an unreadable ledger — reports. */
        static Usage none() {
            return new Usage(0L, 0);
        }
    }

    /**
     * Deliberately NO {@code archived_at} filter. Ten ledger reads beside this one have one, and every
     * one of them is right to: they answer "what does this review's page show", and a purged review has
     * no page. This one answers "what has already been spent", and money spent is not un-spent by a row
     * being tidied away — a filter here would make archiving, and later purging, a way to hand budget
     * back, so anyone who can clear a review can defeat the cap.
     *
     * <p>Two axes, because ADR-023 established that a money-denominated cap is inert by design on an
     * {@code UNMETERED} deployment where every charge is an asserted zero. The pair also closes the
     * ADR-023 hole exactly: an {@code UNKNOWN}-priced row carries a NULL cost that {@code SUM} skips,
     * and the call count catches it anyway.
     */
    private static final String USAGE_SINCE = """
            SELECT COALESCE(SUM(cost_millicents), 0) AS spent,
                   COUNT(DISTINCT call_ref)          AS calls
              FROM llm_charge
             WHERE priced_at >= ?
            """;

    @Inject
    DataSource dataSource;

    /**
     * Deployment-wide usage since {@code from}.
     *
     * <p>A read failure answers {@link Usage#none()} and logs at ERROR — fail open. A cap that refuses
     * every review because its own query failed is worse than a cap that misses a window: the first is
     * an outage that looks like policy, the second is a bounded overshoot. The fault is not swallowed
     * either — it is logged here, and the ledger write path shares this connection pool, so an outage
     * this read hits is already being reported by the writer.
     */
    public Usage since(Instant from) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(USAGE_SINCE)) {
            ps.setTimestamp(1, Timestamp.from(from));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Usage(rs.getLong("spent"), rs.getInt("calls")) : Usage.none();
            }
        } catch (SQLException e) {
            LOG.errorf(e, "Could not read spend since %s — allowing the review rather than refusing it",
                    from);
            return Usage.none();
        }
    }
}
