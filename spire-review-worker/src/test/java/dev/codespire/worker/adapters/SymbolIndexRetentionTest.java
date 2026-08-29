package dev.codespire.worker.adapters;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduled retention sweep (ADR-026 §7.4).
 *
 * <p>Driven directly rather than through {@code @Scheduled}: what needs proving is that the sweep
 * calls the prune, since a one-line regression there is invisible — the index would simply grow
 * forever, which no other test or alert would notice.
 */
@QuarkusTest
class SymbolIndexRetentionTest {

    private static final String REPO = "TEST-WS/TEST-RETENTION";

    @Inject
    SymbolIndexRetention retention;

    @Inject
    PostgresSymbolIndex index;

    @Inject
    DataSource dataSource;

    @Test
    void theSweepRemovesAgedRowsAndKeepsTheRest() {
        sql("DELETE FROM code_symbol WHERE repo = '" + REPO + "'");
        index.record(REPO, "src/Fresh.java", "TESTSHA", List.of(), List.of("Swept"));
        index.record(REPO, "src/Stale.java", "TESTSHA", List.of(), List.of("Swept"));
        sql("UPDATE code_symbol SET last_seen_at = now() - interval '400 days' "
                + "WHERE repo = '" + REPO + "' AND path = 'src/Stale.java'");

        retention.sweep();

        // Pruning costs recall, never correctness — the aged caller simply goes unmentioned.
        assertEquals(List.of("src/Fresh.java"), index.callersOf(REPO, "Swept"));
    }

    @Test
    void theSweepIsSafeWhenThereIsNothingToPrune() {
        sql("DELETE FROM code_symbol WHERE repo = '" + REPO + "'");
        index.record(REPO, "src/Fresh.java", "TESTSHA", List.of(), List.of("Swept"));

        retention.sweep();

        assertTrue(index.callersOf(REPO, "Swept").contains("src/Fresh.java"));
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
