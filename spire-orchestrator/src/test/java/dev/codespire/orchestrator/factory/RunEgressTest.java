package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The hosts a run's agent asked its egress proxy for, stored per run in {@code run_egress} (V106) from the result
 * that carried them. This table is how the hosts a harness really calls are measured before enforce is switched on.
 */
@QuarkusTest
class RunEgressTest {

    private static final String REFUSED_HOST = "test-undeclared.example.test";

    private static final List<RunResult.EgressAttempt> SEEN = List.of(
            new RunResult.EgressAttempt("api.openai.com", 443, true),
            new RunResult.EgressAttempt(REFUSED_HOST, 443, false));

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    /** One stored attempt, as the operator reads it. */
    private record Row(String host, int port, boolean allowed) {
    }

    @Test
    void aFinishedRunsEgressIsStored() throws SQLException {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of(), List.of(), null, false, SEEN));

        assertEquals(List.of(new Row("api.openai.com", 443, true), new Row(REFUSED_HOST, 443, false)), rows(runId));
    }

    /** A run that failed on a refusal names the host in its cause's detail AND keeps the attempt as a row. */
    @Test
    void aBlockedRunsRefusalIsStored() throws SQLException {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFailed(runId, RunFailureCause.BLOCKED_EGRESS.name(),
                "the egress proxy refused " + REFUSED_HOST, false, null, SEEN));

        assertEquals(List.of(new Row("api.openai.com", 443, true), new Row(REFUSED_HOST, 443, false)), rows(runId));
    }

    /**
     * A result is redelivered on a restart, so storing it twice keeps one row per host and the first time it was
     * seen: the measurement counts hosts, not deliveries.
     */
    @Test
    void aRedeliveredResultStoresEachHostOnceAndKeepsWhenItWasFirstSeen() throws SQLException {
        String runId = queuedRun();
        RunResult.RunFinished finished =
                new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of(), List.of(), null, false, SEEN);

        projection.apply(finished);
        List<Timestamp> first = firstSeen(runId);
        projection.apply(finished);

        assertEquals(2, rows(runId).size());
        assertEquals(first, firstSeen(runId));
    }

    /** A result with no egress (no proxy, or an old worker) stores nothing, and is not an error. */
    @Test
    void aResultWithNoEgressStoresNothing() throws SQLException {
        String runId = queuedRun();

        projection.apply(new RunResult.RunFinished(runId, null, List.of(), List.of(), null, false, List.of()));

        assertEquals(List.of(), rows(runId));
    }

    // ---- V106 ------------------------------------------------------------------------------

    @Test
    void theTableHoldsOneRowPerRunHostAndPort() throws SQLException {
        String runId = queuedRun();
        insert(runId, "api.openai.com", 443, true);

        assertThrows(SQLException.class, () -> insert(runId, "api.openai.com", 443, false));
        insert(runId, "api.openai.com", 80, true);
        assertEquals(2, rows(runId).size());
    }

    @Test
    void theTableRefusesAPortThatIsNotOneAndAnUnknownRun() throws SQLException {
        String runId = queuedRun();

        assertThrows(SQLException.class, () -> insert(runId, "api.openai.com", 0, true));
        assertThrows(SQLException.class, () -> insert(runId, "api.openai.com", 65536, true));
        assertThrows(SQLException.class, () -> insert(runId, "", 443, true));
        assertThrows(SQLException.class, () -> insert("run::github:TEST-acme/app:no-such-run:1", "api.openai.com", 443, true),
                "an attempt belongs to a run that exists");
    }

    private String queuedRun() {
        String runId = "run::github:TEST-acme/app:egress-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main",
                "abc1234", "spire/x", "spire-bot", null), null, null);
        return runId;
    }

    private void insert(String runId, String host, int port, boolean allowed) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO run_egress(run_id, host, port, allowed) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, runId);
            ps.setString(2, host);
            ps.setInt(3, port);
            ps.setBoolean(4, allowed);
            ps.executeUpdate();
        }
    }

    private List<Row> rows(String runId) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT host, port, allowed FROM run_egress WHERE run_id = ? ORDER BY host, port")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new Row(rs.getString("host"), rs.getInt("port"), rs.getBoolean("allowed")));
            }
        }
        return rows;
    }

    private List<Timestamp> firstSeen(String runId) throws SQLException {
        List<Timestamp> seen = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT first_seen_at FROM run_egress WHERE run_id = ? ORDER BY host, port")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) seen.add(rs.getTimestamp("first_seen_at"));
            }
        }
        return seen;
    }
}
