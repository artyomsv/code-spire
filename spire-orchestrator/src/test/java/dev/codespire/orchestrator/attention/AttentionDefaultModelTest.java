package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default review model's rows: none chosen (blocking), one of another vendor than the default provider, one switched
 * off (warnings). Each test reads only its own code, on a reset provider and model table.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AttentionDefaultModelTest {

    /** This suite's default model; its own name, so another suite's catalog rows cannot decide a row here. */
    private static final String MODEL = "TEST-ATTENTION-DEFAULT-MODEL";

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void reset() {
        sql("DELETE FROM llm_provider");
        sql("DELETE FROM app_setting WHERE key = 'llm.default-model'");
        sql("DELETE FROM llm_model WHERE name = '" + MODEL + "'");
    }

    /** A default provider with no default review model refuses every review on it. */
    @Test
    void aDefaultProviderWithNoDefaultModelIsReportedAsBlocking() {
        catalogueMeteredModelWithRates();
        insertLlmProvider(TestLlmProvider.enabledDefault("TEST-llm"));
        sql("DELETE FROM app_setting WHERE key = 'llm.default-model'");

        AttentionView row = queries.collect().stream()
                .filter(v -> "LLM_DEFAULT_MODEL_MISSING".equals(v.code()))
                .findFirst().orElseThrow(() -> new AssertionError("no row in " + queries.collect()));
        assertEquals(AttentionView.Severity.BLOCKING, row.severity());
        assertEquals("/settings/llm", row.action());
        assertFalse(codes().contains("MODEL_NOT_PRICEABLE"), "nothing to price when no model is chosen");
    }

    /** A gateway setup: the default model is of another vendor than its provider. Runs, with a warning. */
    @Test
    void aDefaultModelOfAnotherVendorIsAWarningNamingTheDefault() {
        catalogueMeteredModelWithRates();
        insertLlmProvider(TestLlmProvider.enabledDefault("TEST-llm").ofType("anthropic"));

        AttentionView row = queries.collect().stream()
                .filter(v -> "LLM_MODEL_NOT_FOR_PROVIDER".equals(v.code()))
                .findFirst().orElseThrow(() -> new AssertionError("no row in " + queries.collect()));
        assertEquals(AttentionView.Severity.WARNING, row.severity());
        assertNull(row.subject(), "the default has no repository subject");
        assertTrue(row.message().contains(MODEL) && row.message().contains("the default review model"), row.message());
    }

    /** A switched-off default model still runs for API reviews; the operator is asked to choose another. */
    @Test
    void aSwitchedOffDefaultModelIsAWarningNamingTheDefault() {
        catalogueMeteredModelWithRates();
        sql("UPDATE llm_model SET enabled = FALSE WHERE name = '" + MODEL + "'");
        insertLlmProvider(TestLlmProvider.enabledDefault("TEST-llm"));

        AttentionView row = queries.collect().stream()
                .filter(v -> "LLM_MODEL_SWITCHED_OFF".equals(v.code()))
                .findFirst().orElseThrow(() -> new AssertionError("no row in " + queries.collect()));
        assertEquals(AttentionView.Severity.WARNING, row.severity());
        assertTrue(row.message().contains(MODEL) && row.message().contains("the default review model"), row.message());
        assertFalse(codes().contains("LLM_MODEL_NOT_FOR_PROVIDER"), codes().toString());
    }

    private Set<String> codes() {
        return queries.collect().stream().map(AttentionView::code).collect(Collectors.toSet());
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }

    /** Writes the row; a default provider gets {@link #MODEL} as the default review model. */
    private void insertLlmProvider(TestLlmProvider provider) {
        provider.statements(MODEL).forEach(this::sql);
    }

    /** {@link #MODEL}, METERED with input and output rates of 1: obviously synthetic, not any vendor's price. */
    private void catalogueMeteredModelWithRates() {
        sql("INSERT INTO llm_model (id, type, name, label, pricing_mode) VALUES ('"
                + UUID.randomUUID() + "', 'openai', '" + MODEL + "', '" + MODEL + "', 'METERED')");
        sql("INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million) "
                + "SELECT id, 'INPUT', 1 FROM llm_model WHERE name = '" + MODEL + "'");
        sql("INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million) "
                + "SELECT id, 'OUTPUT', 1 FROM llm_model WHERE name = '" + MODEL + "'");
    }
}
