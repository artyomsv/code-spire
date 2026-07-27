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
        sql("DELETE FROM context_provider");
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

    /** A review that has not moved is the closest honest signal that deliveries stopped arriving. */
    @Test
    void aReviewStuckPastTheThresholdIsReportedWithItsCount() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "reviewing", "OPEN", "2 hours");
        insertReview("TEST-r2", "reviewing", "OPEN", "2 hours");
        AttentionView row = queries.collect().stream()
                .filter(v -> "REVIEW_STUCK".equals(v.code()))
                .findFirst().orElseThrow();
        assertTrue(row.message().contains("2"), row.message());
        assertEquals("/", row.action());
    }

    /** A review that is merely young is not stuck. */
    @Test
    void aRecentInProgressReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "reviewing", "OPEN", "1 minute");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** Cancel-on-close should have ended it; alerting about a merged PR is how a panel becomes noise. */
    @Test
    void aStuckReviewOnAClosedPrIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "reviewing", "MERGED", "2 hours");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /**
     * A terminal review is not stuck, however old.
     *
     * <p>The status values here are the READ MODEL's own lowercase vocabulary, which is what
     * {@code ReviewProjection} actually writes — NOT {@code ReviewState.Status}'s uppercase enum
     * names. These fixtures originally used the enum spelling, which matched the query's spelling,
     * so the pair agreed with each other and disagreed with production: on a real database every
     * completed review on an open PR reported as stuck and a genuinely failed one was invisible.
     * Keep these lowercase — they are the regression guard for that.
     */
    @Test
    void anOldCompletedReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "completed", "OPEN", "30 days");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /**
     * A run replaced by a newer commit is finished, not stalled. {@code superseded} was missing
     * from the terminal set entirely, independently of the casing bug.
     */
    @Test
    void aSupersededReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "superseded", "OPEN", "2 hours");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** A cancelled review is terminal too. */
    @Test
    void aCancelledReviewIsNotReportedAsStuck() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "cancelled", "OPEN", "2 hours");
        assertFalse(codes().contains("REVIEW_STUCK"), codes().toString());
    }

    /** Recent failures are actionable. */
    @Test
    void aRecentlyFailedReviewIsReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "1 hour");
        assertTrue(codes().contains("REVIEW_FAILED"), codes().toString());
    }

    /**
     * With no dismiss button anywhere in this design, an unwindowed failure row would nag
     * forever. The window is what makes it self-clearing.
     */
    @Test
    void aFailureOlderThanTheWindowIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "30 days");
        assertFalse(codes().contains("REVIEW_FAILED"), codes().toString());
    }

    /** A credential the provider refused is the case that started this feature. */
    @Test
    void aRejectedScmCredentialIsReportedByProviderName() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET last_check_at = now(), last_check_ok = FALSE, "
                + "last_check_error = 'Authentication rejected (HTTP 401)' WHERE name = 'TEST-scm'");
        AttentionView row = queries.collect().stream()
                .filter(v -> "CREDENTIAL_REJECTED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("TEST-scm", row.subject());
        assertEquals("/settings/providers", row.action());
        assertTrue(row.message().contains("401"), row.message());
    }

    /** A rejected LLM key routes the operator to the LLM page, not the SCM page. */
    @Test
    void aRejectedLlmCredentialLinksToTheLlmSettingsPage() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE llm_provider SET last_check_at = now(), last_check_ok = FALSE, "
                + "last_check_error = 'The LLM provider rejected the API key' WHERE name = 'TEST-llm'");
        AttentionView row = queries.collect().stream()
                .filter(v -> "CREDENTIAL_REJECTED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("/settings/llm", row.action());
    }

    /**
     * NULL means never checked, which is not a problem. Only an explicit FALSE raises a row —
     * otherwise every provider whose Check button was never pressed would nag forever.
     */
    @Test
    void anUncheckedCredentialIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }

    /** A passing check clears the row; there is no separate clear action. */
    @Test
    void aPassingCheckClearsTheRejection() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET last_check_ok = FALSE WHERE name = 'TEST-scm'");
        assertTrue(codes().contains("CREDENTIAL_REJECTED"));
        sql("UPDATE scm_provider SET last_check_ok = TRUE WHERE name = 'TEST-scm'");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }

    /** A disabled provider cannot break a review, so its dead credential is not actionable. */
    @Test
    void aDisabledProvidersRejectedCredentialIsNotReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        sql("UPDATE scm_provider SET enabled = FALSE, last_check_ok = FALSE WHERE name = 'TEST-scm'");
        assertFalse(codes().contains("CREDENTIAL_REJECTED"), codes().toString());
    }

    /**
     * The third of {@code credentialRows}' three near-identical table/action pairs — the one a
     * copy-paste transposition would land on undetected without pinning both subject and action.
     */
    @Test
    void aRejectedContextCredentialLinksToTheContextSettingsPage() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertContextProvider("TEST-context");
        sql("UPDATE context_provider SET last_check_ok = FALSE WHERE name = 'TEST-context'");
        AttentionView row = queries.collect().stream()
                .filter(v -> "CREDENTIAL_REJECTED".equals(v.code()))
                .findFirst().orElseThrow();
        assertEquals("TEST-context", row.subject());
        assertEquals("/settings/context", row.action());
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

    private void insertContextProvider(String name) {
        sql("INSERT INTO context_provider (id, name, type, base_url, auth_kind, auth_secret, enabled) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + name + "', 'jira', "
                + "'https://context.example.invalid', 'bearer', 'TEST-SECRET', TRUE)");
    }

    private void insertDlqEntry(String status) {
        sql("INSERT INTO dlq_entry (id, kafka_key, message_type, original_topic, reason, payload, status) "
                + "VALUES ('" + UUID.randomUUID() + "', 'TEST-KEY', 'TEST-TYPE', 'cs.commands', "
                + "'TEST-REASON', '{}', '" + status + "')");
    }

    /** {@code age} is a Postgres interval literal, e.g. "2 hours". */
    private void insertReview(String reviewId, String status, String prState, String age) {
        sql("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, pr_state, "
                + "created_at, updated_at) VALUES ('" + reviewId + "', 'TEST-WS', 'TEST-REPO', 1, '"
                + status + "', '" + prState + "', now() - interval '" + age + "', "
                + "now() - interval '" + age + "')");
    }
}
