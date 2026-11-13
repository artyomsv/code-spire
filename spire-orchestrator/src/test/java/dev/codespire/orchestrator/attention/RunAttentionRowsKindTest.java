package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.factory.HarnessCredentialPool;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each key kind is its own pool (harness reviews 3b): Claude Code pays only with Anthropic keys and Codex only with
 * OpenAI ones, so a spent kind blocks its own harness while the other still runs, and the row says which.
 */
@QuarkusTest
class RunAttentionRowsKindTest {

    @Inject
    RunAttentionRows attention;

    @Inject
    HarnessCredentialPool pool;

    @Inject
    DataSource dataSource;

    private final List<UUID> added = new ArrayList<>();

    @BeforeEach
    void onlyOurMembers() {
        sql("UPDATE harness_credential SET enabled = FALSE");
    }

    @AfterEach
    void deleteOurMembers() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM harness_credential WHERE id = ?")) {
            for (UUID id : added) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cleanup failed", e);
        }
        added.clear();
    }

    private UUID member(String kind) {
        UUID id = pool.add("TEST-attention-" + kind + "-" + UUID.randomUUID(), kind,
                "https://TEST-" + kind + ".invalid", "TEST-agent-key-" + kind).id();
        added.add(id);
        return id;
    }

    @Test
    void aSpentKindRaisesAttentionBesideAHealthyOne() throws SQLException {
        pool.markRejected(member("anthropic"));
        pool.markRejected(member("anthropic"));
        member("openai");

        List<AttentionView> rows = poolRows();

        assertEquals(1, rows.size(), rows.toString());
        AttentionView row = rows.getFirst();
        assertEquals("anthropic", row.subject(), "the row names the spent kind");
        assertTrue(row.message().contains("anthropic"), row.message());
        assertFalse(row.message().contains("no factory run can start"),
                "an OpenAI key still serves, so runs paid by it still start: " + row.message());
    }

    private List<AttentionView> poolRows() throws SQLException {
        List<AttentionView> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            attention.collect(c, rows);
        }
        return rows.stream().filter(r -> RunAttentionRows.POOL_CODE.equals(r.code())).toList();
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
