package dev.codespire.orchestrator.readmodel;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** V32 adds the archival marker to the review row and to the ledger. */
@QuarkusTest
class ReviewArchivalSchemaIT {

    @Inject
    DataSource dataSource;

    private boolean hasColumn(String table, String column) throws SQLException {
        String sql = """
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'orchestrator' AND table_name = ? AND column_name = ?
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void reviewStatusAndTheLedgerBothCarryAnArchivalMarker() throws SQLException {
        assertTrue(hasColumn("review_status", "archived_at"), "review_status.archived_at");
        assertTrue(hasColumn("llm_charge", "archived_at"), "llm_charge.archived_at");
    }
}
