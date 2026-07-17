package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReviewThreadLinkTest {

    @Inject
    ReviewThreadView threads;

    @Inject
    DataSource dataSource;

    @Test
    void findingThreadStoresPathAndLineAndOwnership() throws Exception {
        String id = "review::acme/web#7001";
        threads.markFindingThread(id, new ThreadRef("c1"), "src/App.java", 9);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT path, line, is_ours, is_summary FROM review_thread "
                             + "WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, id);
            ps.setString(2, "c1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("src/App.java", rs.getString("path"));
                assertEquals(9, rs.getInt("line"));
                assertTrue(rs.getBoolean("is_ours"), "a finding thread is one of ours (scope A)");
                assertFalse(rs.getBoolean("is_summary"));
            }
        }
    }

    @Test
    void summaryThreadIsFlaggedButNotMarkedOurs() throws Exception {
        String id = "review::acme/web#7002";
        threads.markSummaryThread(id, new ThreadRef("sum1"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_ours, is_summary FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, id);
            ps.setString(2, "sum1");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("is_summary"));
                assertFalse(rs.getBoolean("is_ours"),
                        "display-only: the summary thread must NOT become 'ours' (no scope change)");
            }
        }
    }
}
