package dev.codespire.runworker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * The run worker's only idempotency mechanism.
 *
 * <p>The command channel acks on RECEIPT, because an hour-long run cannot ride an
 * ordered-blocking channel — that pairing once stalled a consumer which then re-stalled on every
 * restart and needed a manual offset seek. So a redelivery is not stopped by Kafka; it is stopped
 * here, and the write order matters: claim FIRST, then ack.
 */
@ApplicationScoped
public class RunClaimStore {

    @Inject
    DataSource dataSource;

    /** @return true when THIS caller took the slot; false when it was already taken. */
    public boolean claim(String runId, String slot) {
        String sql = """
                INSERT INTO runworker.run_claim (run_id, slot)
                VALUES (?, ?)
                ON CONFLICT (run_id, slot) DO NOTHING
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, runId);
            statement.setString(2, slot);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            // Fail CLOSED. An unreadable claim table must not authorise a paid run: answering
            // "true" on a database fault turns one outage into an unbounded number of duplicate
            // agent runs, which is the shape the LLM idempotency claim already learned from.
            throw new IllegalStateException("could not take the run claim for " + runId, e);
        }
    }
}
