package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every panel row is a query result, so the contract under test is "which codes does this
 * state produce". The empty-system case matters most: a condition that fires unconditionally
 * would still look correct in every other test.
 */
@QuarkusTest
class AttentionQueriesTest {

    @Inject
    AttentionQueries queries;

    @Inject
    DataSource dataSource;

    private Set<String> codes() {
        return queries.collect().stream().map(AttentionView::code).collect(java.util.stream.Collectors.toSet());
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }

    @BeforeEach
    void reset() {
        sql("DELETE FROM llm_provider");
        sql("DELETE FROM provider_author");
        sql("DELETE FROM scm_provider");
        sql("DELETE FROM dlq_entry");
        sql("DELETE FROM review_event");
        sql("DELETE FROM review_status");
    }

    /** An operator with nothing configured is blocked on both registries at once. */
    @Test
    void anEmptySystemReportsBothMissingProviderBlockers() {
        Set<String> found = codes();
        assertTrue(found.contains("LLM_PROVIDER_MISSING"), found.toString());
        assertTrue(found.contains("SCM_PROVIDER_MISSING"), found.toString());
        // Not this one: with no providers at all, "no default" is not the actionable problem.
        assertFalse(found.contains("LLM_DEFAULT_MISSING"), found.toString());
    }

    /** A fully configured system must produce NOTHING. This is the unconditional-firing guard. */
    @Test
    void aFullyConfiguredSystemReportsNothing() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertEquals(List.of(), queries.collect());
    }

    /** An enabled provider exists but nothing is marked default, so brokering cannot pick one. */
    @Test
    void enabledLlmProvidersWithNoDefaultReportTheMissingDefault() {
        insertLlmProvider("TEST-llm", true, false);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        Set<String> found = codes();
        assertTrue(found.contains("LLM_DEFAULT_MISSING"), found.toString());
        assertFalse(found.contains("LLM_PROVIDER_MISSING"), found.toString());
    }

    /** Disabling the default is as blocking as never setting one -- the resolver requires both. */
    @Test
    void aDisabledDefaultLlmProviderStillReportsTheMissingDefault() {
        insertLlmProvider("TEST-llm-off", false, true);
        insertLlmProvider("TEST-llm-on", true, false);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertTrue(codes().contains("LLM_DEFAULT_MISSING"), codes().toString());
    }

    /** The bot cannot recognise its own comments without an identity, so conversation breaks. */
    @Test
    void anScmProviderWithNoBotIdentityIsReportedByName() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-nameless", "", null);
        AttentionView row = queries.collect().stream()
                .filter(v -> "BOT_IDENTITY_UNRESOLVED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("TEST-nameless", row.subject());
        assertEquals(AttentionView.Severity.WARNING, row.severity());
    }

    /** Either identity field alone is enough -- only a provider with neither is unresolved. */
    @Test
    void anScmProviderWithOnlyAUsernameIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "", "test-bot");
        assertFalse(codes().contains("BOT_IDENTITY_UNRESOLVED"), codes().toString());
    }

    /** Dropped work must be visible; the row carries the count so one row covers any number. */
    @Test
    void pendingDeadLetterEntriesAreReportedWithTheirCount() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertDlqEntry("pending");
        insertDlqEntry("pending");
        insertDlqEntry("discarded");
        AttentionView row = queries.collect().stream()
                .filter(v -> "DLQ_PENDING".equals(v.code()))
                .findFirst().orElseThrow();
        assertTrue(row.message().contains("2"), row.message());
        assertEquals("/settings/dlq", row.action());
    }

    /** Blockers must sort ahead of warnings -- the operator reads the top of the list. */
    @Test
    void blockingRowsSortBeforeWarnings() {
        insertScmProvider("TEST-nameless", "", null);
        List<AttentionView> rows = queries.collect();
        int firstWarning = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).severity() == AttentionView.Severity.WARNING) {
                firstWarning = i;
                break;
            }
        }
        assertTrue(firstWarning > 0, "expected at least one blocker before the first warning");
        for (int i = firstWarning; i < rows.size(); i++) {
            assertEquals(AttentionView.Severity.WARNING, rows.get(i).severity());
        }
    }

    // ---- fixtures: obviously-synthetic values only --------------------------

    private void insertLlmProvider(String name, boolean enabled, boolean isDefault) {
        sql("INSERT INTO llm_provider (id, name, type, base_url, api_key, model, temperature, enabled, is_default) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + name + "', 'openai', "
                + "'https://llm.example.invalid', 'TEST-KEY', 'TEST-MODEL', 0.0, "
                + enabled + ", " + isDefault + ")");
    }

    private void insertScmProvider(String name, String botAccountId, String botUsername) {
        sql("INSERT INTO scm_provider (id, name, type, base_url, workspace, auth_kind, auth_secret, "
                + "bot_account_id, bot_username, enabled) VALUES ('" + UUID.randomUUID() + "', '" + name
                + "', 'stub', 'https://scm.example.invalid', 'TEST-WS', 'bearer', 'TEST-SECRET', '"
                + botAccountId + "', " + (botUsername == null ? "NULL" : "'" + botUsername + "'") + ", TRUE)");
    }

    private void insertDlqEntry(String status) {
        sql("INSERT INTO dlq_entry (id, kafka_key, message_type, original_topic, reason, payload, status) "
                + "VALUES ('" + UUID.randomUUID() + "', 'TEST-KEY', 'TEST-TYPE', 'cs.commands', "
                + "'TEST-REASON', '{}', '" + status + "')");
    }
}
