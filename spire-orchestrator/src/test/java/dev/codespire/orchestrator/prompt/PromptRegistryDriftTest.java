package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A customized template is a fork with no recorded common ancestor unless {@code drift} can name
 * one. A row with no recorded ancestor (predates V33) must report unknown, never a confident
 * up-to-date -- that's the distinction {@link PromptRegistry.Drift}'s two booleans exist to keep
 * separate.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class PromptRegistryDriftTest {

    @Inject
    PromptRegistry registry;

    @Inject
    DataSource dataSource;

    @AfterEach
    void resetCustomizedKinds() {
        registry.reset(PromptKind.REVIEW);
        registry.reset(PromptKind.RECONCILE);
    }

    @Test
    void aFreshCustomizationHasNotDrifted() {
        registry.save(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertTrue(drift.baseKnown());
        assertFalse(drift.defaultDrifted());
    }

    @Test
    void theStoredAncestorIsTheBuiltInDefaultNotTheCustomization() {
        registry.save(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertEquals(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system(), drift.baseSystem());
        assertNotEquals("My persona", drift.baseSystem());
    }

    @Test
    void aRowWithNoRecordedAncestorReportsUnknownNotUpToDate() {
        // Every row written before V33. Reporting "up to date" would be a confident claim about
        // state nobody recorded.
        insertLegacyRowWithoutBase(PromptKind.REVIEW, "Old persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);

        assertFalse(drift.baseKnown());
        assertFalse(drift.defaultDrifted()); // unknowable, so not asserted either way
    }

    @Test
    void acceptingTheCurrentDefaultClearsDriftAndKeepsTheCustomization() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");
        assertTrue(registry.drift(PromptKind.REVIEW).defaultDrifted());

        registry.acceptCurrentDefault(PromptKind.REVIEW);

        assertFalse(registry.drift(PromptKind.REVIEW).defaultDrifted());
        assertEquals("My persona", registry.effective(PromptKind.REVIEW).system());
    }

    @Test
    void anUncustomizedKindNeverReportsDrift() {
        registry.reset(PromptKind.RECONCILE);

        assertFalse(registry.drift(PromptKind.RECONCILE).defaultDrifted());
        assertTrue(registry.drift(PromptKind.RECONCILE).baseKnown());
    }

    /**
     * Not one of the brief's five, added because none of them exercises save's UPDATE branch: both
     * given {@code save} calls are the first write for that kind, so they always take the INSERT
     * path. An operator re-saving an already-customized kind is re-forking from whatever ships now,
     * so the ancestor must move forward with them, not stay pinned to whatever it was at first save.
     */
    @Test
    void reSavingAnAlreadyCustomizedKindReStampsTheAncestor() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");
        assertTrue(registry.drift(PromptKind.REVIEW).defaultDrifted());

        registry.save(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW);
        assertFalse(drift.defaultDrifted());
        assertEquals(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system(), drift.baseSystem());
    }

    /** A row as V23 alone would have written it -- no ancestor column populated. */
    private void insertLegacyRowWithoutBase(PromptKind kind, String system, String body) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template (kind, system_text, body_text, updated_at)
                     VALUES (?, ?, ?, now())
                     ON CONFLICT (kind) DO UPDATE
                         SET system_text       = EXCLUDED.system_text,
                             body_text         = EXCLUDED.body_text,
                             base_system_text  = NULL,
                             base_body_text    = NULL,
                             updated_at        = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert legacy row for " + kind.slug(), e);
        }
    }

    private void insertRowWithBase(PromptKind kind, String system, String body,
            String baseSystem, String baseBody) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (kind) DO UPDATE
                         SET system_text       = EXCLUDED.system_text,
                             body_text         = EXCLUDED.body_text,
                             base_system_text  = EXCLUDED.base_system_text,
                             base_body_text    = EXCLUDED.base_body_text,
                             updated_at        = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.setString(4, baseSystem);
            ps.setString(5, baseBody);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert row with base for " + kind.slug(), e);
        }
    }
}
