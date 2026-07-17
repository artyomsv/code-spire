package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewEventThreadRefTest {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void conversationAppendPersistsThreadRefAndPlainAppendLeavesItNull() throws Exception {
        String id = "review::acme/web#7101";
        projection.appendEvent(id, "integration", "AuthorReplied", "@bob: why?", "c1");
        projection.appendEvent(id, "integration", "PullRequestEventReceived", "opened"); // 4-arg → null

        assertEquals("c1", threadRefOf(id, "AuthorReplied"));
        assertNull(threadRefOf(id, "PullRequestEventReceived"));
    }

    private String threadRefOf(String reviewId, String type) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT thread_ref FROM review_event WHERE review_id = ? AND type = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString("thread_ref");
            }
        }
    }
}
