package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V109: a repository's harness review setting is whole or absent, pays in a known way, and never sits beside an API
 * provider. Written as raw SQL for the reason {@code FactoryRunReviewKindsTest} gives: the SCHEMA holds the rule, not
 * one careful writer.
 */
@QuarkusTest
class RepositoryReviewHarnessSchemaTest {

    private static final String CHECK = "repository_review_harness_whole";

    @Inject
    DataSource dataSource;

    @Inject
    LlmProviderRegistry llmProviders;

    @Inject
    LlmModelRegistry llmModels;

    private final String workspace = "TEST-V109-" + UUID.randomUUID();

    private final List<UUID> createdProviders = new ArrayList<>();

    @Test
    void aWholeHarnessRowIsAdmitted() {
        assertDoesNotThrow(() -> insert(new Row("codex", "TEST-model", "high", "SUBSCRIPTION", null)));
        assertDoesNotThrow(() -> insert(new Row("codex", "TEST-model", null, "API_KEY", null)));
        assertDoesNotThrow(() -> insert(new Row(null, null, null, null, null)));
    }

    @Test
    void aPartialHarnessRowIsRefused() {
        IllegalStateException noModel = assertThrows(IllegalStateException.class,
                () -> insert(new Row("codex", null, null, "API_KEY", null)));
        IllegalStateException blankModel = assertThrows(IllegalStateException.class,
                () -> insert(new Row("codex", " ", null, "API_KEY", null)));
        IllegalStateException noPayment = assertThrows(IllegalStateException.class,
                () -> insert(new Row("codex", "TEST-model", null, null, null)));
        IllegalStateException effortAlone = assertThrows(IllegalStateException.class,
                () -> insert(new Row(null, null, "high", null, null)));

        for (IllegalStateException refused : List.of(noModel, blankModel, noPayment, effortAlone)) {
            assertTrue(rootCause(refused).contains(CHECK), rootCause(refused));
        }
    }

    @Test
    void aHarnessBesideAProviderIsRefused() {
        UUID provider = llmProvider();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert(new Row("codex", "TEST-model", null, "API_KEY", provider)));

        assertTrue(rootCause(refused).contains(CHECK), rootCause(refused));
        assertDoesNotThrow(() -> insert(new Row(null, null, null, null, provider)), "a provider alone stays allowed");
    }

    @Test
    void anUnknownPaymentIsRefused() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> insert(new Row("codex", "TEST-model", null, "TEST-CREDIT-CARD", null)));

        assertTrue(rootCause(refused).contains(CHECK), rootCause(refused));
    }

    /** {@code review_status.harness_refusal} exists and is nullable, so every review row written today still fits. */
    @Test
    void theReviewStatusCarriesAHarnessRefusal() throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name='review_status' AND column_name='harness_refusal'""")) {
            try (java.sql.ResultSet rows = ps.executeQuery()) {
                assertTrue(rows.next(), "review_status has no harness_refusal column");
                assertTrue("YES".equals(rows.getString(1)), "harness_refusal must be nullable");
            }
        }
    }

    private record Row(String harness, String model, String effort, String payWith, UUID provider) {
    }

    private void insert(Row row) {
        String sql = """
                INSERT INTO repository (id, scm_type, forge_origin, workspace, slug, review_harness,
                                        review_harness_model, review_harness_effort, review_harness_pay_with,
                                        review_llm_provider_id)
                VALUES (?, 'github', 'https://forge.example.test', ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, workspace);
            ps.setString(3, "TEST-repo-" + UUID.randomUUID());
            ps.setString(4, row.harness());
            ps.setString(5, row.model());
            ps.setString(6, row.effort());
            ps.setString(7, row.payWith());
            ps.setObject(8, row.provider(), Types.OTHER);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private UUID llmProvider() {
        String model = "TEST-v109-model-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("openai", model, model, "UNMETERED", Map.of(),
                null, null, null, null, true, List.of()));
        UUID id = UUID.fromString(llmProviders.create(new LlmProviderInput("TEST-v109", "openai",
                "https://llm.example.invalid", "TEST-key", model, null, null, true, false)).id());
        createdProviders.add(id);
        return id;
    }

    @AfterEach
    void removeRows() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM repository WHERE workspace=?")) {
                ps.setString(1, workspace);
                ps.executeUpdate();
            }
            for (UUID provider : createdProviders) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM llm_provider WHERE id=?")) {
                    ps.setObject(1, provider);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }
}
