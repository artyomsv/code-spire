package dev.codespire.orchestrator.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Bounded fix chains (FR-F32), on two axes, and the attempt a re-dispatch takes.
 *
 * <p><b>Two axes, because one does not bound the loop the requirement is about.</b> Counting per
 * FINDING stops repeated attempts at one stubborn finding. It cannot stop the runaway FR-F32 names —
 * a finding spawns a fix, whose review raises a finding, which spawns a fix — because every hop
 * raises a NEW finding with a new identity, so a per-finding counter sees one run for each and never
 * reaches N while reporting itself satisfied. The per-REVIEW axis bounds the chain, and under ADR-040
 * a fix pushes to the branch the review already watches, so one review IS the chain.
 *
 * <p>The counts come from {@code factory_run} rather than a new table, for the reason
 * {@link dev.codespire.orchestrator.llm.ReviewRuns} counts events rather than storing a column: a
 * derived count cannot drift from the runs it counts, and a stored one has to be kept correct across
 * every path that creates or removes a run.
 *
 * <p><b>A read fault refuses rather than allowing.</b> That is the opposite of this deployment's
 * other unset-means-unlimited defaults, and deliberately: an unset cap is an operator's choice, while
 * an unreadable count is an unknown — and the thing on the other side of this gate is a paid agent
 * with a push token. Unknown is not zero (ADR-023), and here unknown is not "within budget".
 */
@ApplicationScoped
public class FixRuns {

    /**
     * A fix run names its target, and the V54 CHECK is what makes that true.
     *
     * <p><b>The {@code kind} filter here is belt-and-braces, not the guard.</b> A mutation removing
     * it survives every behavioural test, because the constraint makes "carries a review and is not
     * a fix" an unrepresentable state — so no fixture can build the row the filter would exclude.
     * It is kept because that constraint is exactly what loosens when SPEC and PLAN runs arrive
     * (both already in the same CHECK's kind list), and the day one of those carries a review is
     * the day this filter starts doing work. {@code FixRunsTest} asserts the constraint itself, so
     * the load-bearing half is the half that is tested.
     */
    private static final String COUNT_FOR_FINDING = """
                SELECT count(*) FROM factory_run
                 WHERE kind = 'FIX' AND review_id = ? AND finding_ref = ?
                """;

    private static final String COUNT_FOR_REVIEW = """
                SELECT count(*) FROM factory_run
                 WHERE kind = 'FIX' AND review_id = ?
                """;

    @Inject
    DataSource dataSource;

    /** Fix runs already dispatched for one finding. */
    public int forFinding(String reviewId, String findingRef) {
        return count(COUNT_FOR_FINDING, reviewId, findingRef);
    }

    /** Fix runs already dispatched anywhere on one review — the chain. */
    public int forReview(String reviewId) {
        return count(COUNT_FOR_REVIEW, reviewId);
    }

    /**
     * The attempt number a fresh dispatch for this finding should take.
     *
     * <p>{@code RunIds} embeds the attempt, and a run id must be unique or the worker's claim drops
     * the second dispatch as a redelivery — a run that is accepted and never runs. So FR-F32's N is
     * unreachable while every fix for one finding would derive the same id, which is what pinning
     * the attempt to 1 does.
     */
    public int nextAttempt(String reviewId, String findingRef) {
        return forFinding(reviewId, findingRef) + 1;
    }

    /**
     * Whether another fix run may be dispatched.
     *
     * @param perFinding how many runs one finding may have; non-positive means unlimited
     * @param perReview how many runs one review may have; non-positive means unlimited
     */
    public Decision decide(String reviewId, String findingRef, int perFinding, int perReview) {
        if (perFinding > 0 && forFinding(reviewId, findingRef) >= perFinding) {
            return Decision.refused("this finding has already had " + perFinding
                    + " fix run(s) — a further one needs a human to look at why they are not landing");
        }
        if (perReview > 0 && forReview(reviewId) >= perReview) {
            return Decision.refused("this pull request has already had " + perReview
                    + " fix run(s) — the chain is capped so a fix-review-fix loop cannot run away");
        }
        return Decision.ALLOWED;
    }

    /** Allowed, or refused with a reason the author can read. */
    public record Decision(boolean allowed, String why) {

        static final Decision ALLOWED = new Decision(true, "");

        static Decision refused(String why) {
            return new Decision(false, why);
        }
    }

    private int count(String sql, String... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the fix runs already dispatched", e);
        }
    }
}
