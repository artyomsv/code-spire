package dev.codespire.orchestrator.factory;

import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Reads the harness a run was dispatched on (harness reviews, step 8). */
final class RunHarnessRead {

    private static final Logger LOG = Logger.getLogger(RunHarnessRead.class);

    private RunHarnessRead() {
    }

    /** The harness this run was dispatched on, or empty when the row cannot be read. */
    static Optional<String> of(DataSource dataSource, String runId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT harness FROM factory_run WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("harness")) : Optional.empty();
            }
        } catch (SQLException e) {
            // Empty: the caller only names the harness in a refusal, which must still be recorded.
            LOG.errorf(e, "run %s: its harness could not be read", runId);
            return Optional.empty();
        }
    }
}
