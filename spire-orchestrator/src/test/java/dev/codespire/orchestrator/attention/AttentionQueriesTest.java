package dev.codespire.orchestrator.attention;

import io.quarkus.test.security.TestSecurity;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every panel row is a query result, so the contract under test is "which codes does this
 * state produce". The empty-system case matters most: a condition that fires unconditionally
 * would still look correct in every other test.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
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

    /**
     * A review that has not moved is the closest honest signal that deliveries stopped arriving —
     * and each one is a discrete record with its own page, so each gets its own row rather than
     * being collapsed into a count the operator cannot navigate from.
     */
    @Test
    void eachStuckReviewIsReportedAsItsOwnNavigableRow() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "reviewing", "OPEN", "2 hours");
        insertReview("TEST-r2", "reviewing", "OPEN", "2 hours");

        List<AttentionView> stuck = queries.collect().stream()
                .filter(v -> "REVIEW_STUCK".equals(v.code())).toList();

        assertEquals(2, stuck.size(), stuck.toString());
        assertTrue(stuck.stream().anyMatch(v -> "TEST-WS/TEST-REPO#1".equals(v.subject())), stuck.toString());
        assertTrue(stuck.stream().anyMatch(v -> "TEST-WS/TEST-REPO#2".equals(v.subject())), stuck.toString());
        AttentionView first = stuck.getFirst();
        assertEquals("/r/TEST-WS/TEST-REPO/" + prOf("TEST-r1"), first.action());
        assertTrue(first.dismiss().endsWith("/attention-ack"), first.dismiss());
    }

    /**
     * Past the cap the remainder collapses into one summary row that SAYS it is a remainder. A
     * silently truncated list reads as "that is all of them", which is the worst possible impression
     * during a systemic outage.
     */
    @Test
    void beyondTheCapTheRemainderIsSummarisedRatherThanDropped() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        for (int i = 1; i <= 7; i++) {
            insertReview("TEST-r" + i, "reviewing", "OPEN", "2 hours");
        }

        List<AttentionView> stuck = queries.collect().stream()
                .filter(v -> "REVIEW_STUCK".equals(v.code())).toList();

        assertEquals(6, stuck.size(), "5 individual rows plus one remainder row: " + stuck);
        // Identified by what makes it the remainder — no single review — not by position: collect()
        // sorts by subject, and a null subject sorts ahead of the named rows rather than after them.
        AttentionView remainder = stuck.stream()
                .filter(v -> v.subject() == null).findFirst()
                .orElseThrow(() -> new AssertionError("no remainder row in " + stuck));
        assertTrue(remainder.message().contains("2"), remainder.message());
        assertNull(remainder.dismiss(), "a remainder covering several reviews cannot be acknowledged");
        assertEquals(5, stuck.stream().filter(v -> v.subject() != null).count(), stuck.toString());
    }

    /**
     * Acknowledging is the only resolution a past failure has — nothing an operator fixes makes a
     * failed review un-fail — so these two row types are the panel's only dismissable ones.
     */
    @Test
    void anAcknowledgedReviewStopsRaisingItsRow() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "1 hour");
        assertTrue(codes().contains("REVIEW_FAILED"), codes().toString());

        acknowledge("TEST-r1");

        assertFalse(codes().contains("REVIEW_FAILED"), codes().toString());
    }

    /**
     * Scoped to the state the operator saw, not to the review forever. A boolean flag would silence
     * that review permanently, which is how a dismissable alert becomes wallpaper.
     */
    @Test
    void aLaterFailureRaisesTheRowAgainAfterAnAcknowledgement() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "1 hour");
        acknowledge("TEST-r1");
        assertFalse(codes().contains("REVIEW_FAILED"), codes().toString());

        // A fresh failure on the same review moves updated_at past the acknowledgement.
        sql("UPDATE review_status SET updated_at = now() WHERE review_id = 'TEST-r1'");

        assertTrue(codes().contains("REVIEW_FAILED"), codes().toString());
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

    /** A failure is actionable: open it for the reason, re-run it, or acknowledge it. */
    @Test
    void aFailedReviewIsReportedWithItsOwnRow() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "1 hour");

        AttentionView row = queries.collect().stream()
                .filter(v -> "REVIEW_FAILED".equals(v.code()))
                .findFirst().orElseThrow();

        assertEquals("TEST-WS/TEST-REPO#1", row.subject());
        assertEquals("/r/TEST-WS/TEST-REPO/1", row.action());
        assertTrue(row.dismiss().endsWith("/attention-ack"), row.dismiss());
    }

    /**
     * An OLD failure is still reported. There was a 24-hour window here, added because the panel had
     * no dismiss action — but a timer is expiry, not resolution, and it would retire a failure nobody
     * ever saw. Acknowledging replaced it, so age alone no longer silences anything.
     */
    @Test
    void anOldUnacknowledgedFailureIsStillReported() {
        insertLlmProvider("TEST-llm", true, true);
        insertScmProvider("TEST-scm", "acct-1", "test-bot");
        insertReview("TEST-r1", "failed", "OPEN", "30 days");
        assertTrue(codes().contains("REVIEW_FAILED"), codes().toString());
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
        assertTrue(row.action().startsWith("/settings/providers?edit="), row.action());
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
        assertTrue(row.action().startsWith("/settings/llm?edit="), row.action());
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
        assertTrue(row.action().startsWith("/settings/context?edit="), row.action());
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
    /**
     * {@code age} is a Postgres interval literal, e.g. "2 hours". The PR number is derived from the
     * review id so separate reviews get distinct subjects and detail routes — rows are per-review
     * now, so two fixtures sharing a PR would be indistinguishable in an assertion.
     */
    private void insertReview(String reviewId, String status, String prState, String age) {
        sql("INSERT INTO review_status (review_id, workspace, slug, pr_id, status, pr_state, "
                + "created_at, updated_at) VALUES ('" + reviewId + "', 'TEST-WS', 'TEST-REPO', "
                + prOf(reviewId) + ", '" + status + "', '" + prState + "', now() - interval '" + age
                + "', now() - interval '" + age + "')");
    }

    /** Trailing digits of the id, so "TEST-r7" is PR 7. */
    private static int prOf(String reviewId) {
        String digits = reviewId.replaceAll("\\D+", "");
        return digits.isEmpty() ? 1 : Integer.parseInt(digits);
    }

    /** Acknowledge a review the way the resource does, without bumping updated_at. */
    private void acknowledge(String reviewId) {
        sql("UPDATE review_status SET attention_ack_at = now() WHERE review_id = '" + reviewId + "'");
    }
}
