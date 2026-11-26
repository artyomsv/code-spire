package dev.codespire.orchestrator.readmodel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The "responding" mark with the reply it is for (V122, review of PR #457, code-quality/M-1). {@code answering} is one
 * flag per review, but an answer, a refusal or a decline is about one triggering comment: reply A's decline must not
 * clear the mark while reply B's answer is being written. {@link #start} records the comment, {@link #finish} clears
 * the mark only while it is still that comment's.
 *
 * <p>Results that name no triggering comment ({@code FollowUpPosted}, {@code TurnCapNotified}) keep clearing it
 * unconditionally through {@link ReviewProjection#setAnswering}, as do a new run, an archive and a closed pull request.
 * A row set before V122 holds a NULL comment, which any result clears, as before.
 */
@ApplicationScoped
public class AnsweringMarks {

    private static final String START = """
            UPDATE review_status SET answering = TRUE, answering_comment = ? WHERE review_id = ?
            """;
    private static final String FINISH = """
            UPDATE review_status SET answering = FALSE, answering_comment = NULL
             WHERE review_id = ? AND answering AND (answering_comment IS NULL OR answering_comment = ?)
            """;

    @Inject
    DataSource dataSource;

    @Inject
    ReviewProjection projection;

    /** The bot is answering {@code commentId}: the mark shows, and only this comment's result clears it. */
    public void start(String reviewId, String commentId) {
        write(START, commentId, reviewId);
        projection.touch(reviewId);
    }

    /**
     * {@code commentId}'s reply ended without a posted answer: the mark clears when it is still this comment's. A
     * mark another reply set since stays, and a result that arrives after the mark cleared changes nothing.
     */
    public void finish(String reviewId, String commentId) {
        if (write(FINISH, reviewId, commentId) > 0) {
            projection.touch(reviewId);
        }
    }

    private int write(String sql, String first, String second) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, first);
            ps.setString(2, second);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("review_status answering write failed", e);
        }
    }
}
