package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.repository.StartReview;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A repository's "Start review" (V121), read when a review would start. Its own reader rather than one more method on
 * {@code RepositoryRegistry}, which is at its size limit.
 */
@ApplicationScoped
public class StartReviewSetting {

    @Inject
    DataSource dataSource;

    /** The repository's setting; {@link StartReview#DEFAULT} when the row is missing, so nothing waits on a guess. */
    public StartReview of(UUID repositoryId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT start_review FROM repository WHERE id = ?")) {
            statement.setObject(1, repositoryId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? StartReview.valueOf(rows.getString(1)) : StartReview.DEFAULT;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Start review setting read failed for repository " + repositoryId, failure);
        }
    }
}
