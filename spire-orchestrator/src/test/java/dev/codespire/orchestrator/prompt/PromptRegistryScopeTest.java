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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code prompt_template} is now keyed on {@code (scope, kind)}: a repository can override a
 * prompt without touching the global template. Resolution is most-specific-wins -- repo row, then
 * global row, then the built-in {@link PromptCatalog} default -- and both directions of that are
 * asserted, because a resolver that simply ignores global would still pass a test that only checks
 * "repo wins".
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class PromptRegistryScopeTest {

    @Inject
    PromptRegistry registry;

    @Inject
    DataSource dataSource;

    @AfterEach
    void resetScopes() {
        registry.reset(PromptKind.REVIEW);
        registry.reset(PromptKind.REVIEW, "acme/widgets");
        registry.reset(PromptKind.RECONCILE);
    }

    @Test
    void aRepoOverrideBeatsGlobal() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        assertEquals("Repo persona", registry.effective(PromptKind.REVIEW, "acme/widgets").system());
    }

    @Test
    void globalBeatsTheBuiltInDefault() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");

        assertEquals("Global persona", registry.effective(PromptKind.REVIEW, "acme/other").system());
    }

    @Test
    void withNeitherTheBuiltInDefaultApplies() {
        assertEquals(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system(),
                registry.effective(PromptKind.REVIEW, "acme/widgets").system());
    }

    @Test
    void aRepoWithNoRowFallsThroughRatherThanReturningEmpty() {
        // Both directions matter: a test that only checks "repo wins" passes on an implementation
        // that ignores global entirely.
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        assertEquals("Global persona", registry.effective(PromptKind.REVIEW, "acme/unrelated").system());
    }

    @Test
    void resettingARepoScopeLeavesGlobalAlone() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        registry.save(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}");

        registry.reset(PromptKind.REVIEW, "acme/widgets");

        assertEquals("Global persona", registry.effective(PromptKind.REVIEW, "acme/widgets").system());
    }

    @Test
    void anExistingGlobalRowKeepsWorkingAfterTheMigration() {
        insertPreMigrationRow(PromptKind.RECONCILE, "Legacy persona", "{{prior_findings}}\n{{diff}}");

        assertEquals("Legacy persona",
                registry.effective(PromptKind.RECONCILE, "acme/widgets").system());
    }

    @Test
    void aMalformedScopeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptScope.parse("../../etc/passwd"));
    }

    /**
     * Not one of the brief's seven. {@code "../../etc/passwd"} above is already rejected by the
     * leading-character rule alone (it starts with {@code .}), so it cannot falsify the explicit
     * {@code ..} substring check on its own -- an assertion that cannot fail is not coverage. A
     * traversal that starts and ends with an alnum character, and uses only characters the segment
     * pattern otherwise allows (including {@code .}), isolates that check.
     */
    @Test
    void aMidSegmentTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptScope.parse("acme/../secret"));
    }

    /**
     * Not one of the brief's seven. A bare word has no unrelated test exercising it: it is not
     * {@link PromptScope#GLOBAL}, contains no {@code ..}, and matches {@code SEGMENT} on its own
     * (a single alnum run is a valid segment), so only the explicit slash requirement rejects it. A
     * scope {@link PromptScope#of} never produces one -- a stored key with no repository behind it
     * could never be addressed again, the same "unaddressable key" concern {@code ..} exists for.
     */
    @Test
    void aScopeWithNoSlashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptScope.parse("acmewidgets"));
    }

    /**
     * Not one of the brief's seven. Contains a slash and no {@code ..}, so only the segment
     * pattern's leading-character rule can reject it -- isolating that rule from the {@code ..} and
     * slash checks above, neither of which fires here.
     */
    @Test
    void aScopeWithLeadingPunctuationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PromptScope.parse("-acme/widgets"));
    }

    /**
     * Not one of the brief's seven -- added per Ruling 1. {@code drift}/{@code acceptCurrentDefault}
     * now take a scope, and the bug they exist to prevent is a {@code WHERE kind = ?} query silently
     * reading whichever of the two rows Postgres happens to return first. Global is saved undrifted,
     * the repo is given an older recorded ancestor so it drifted; if scope were ignored, both calls
     * below would read the same row and at least one assertion would fail no matter which row that
     * arbitrary read landed on.
     */
    @Test
    void driftIsScopedToTheRowBeingEdited() {
        registry.save(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}");
        insertRowWithBase(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");

        assertEquals(false, registry.drift(PromptKind.REVIEW, PromptScope.GLOBAL).defaultDrifted());
        assertEquals(true, registry.drift(PromptKind.REVIEW, "acme/widgets").defaultDrifted());
    }

    /**
     * Not one of the brief's seven -- added per Ruling 1. {@code driftIsScopedToTheRowBeingEdited}
     * alone does not distinguish a scope-exact {@code drift} from one that falls back to global like
     * {@code effective} does: both scopes had their own row there, so a fallback implementation would
     * happen to read the same (correct) row it was asked for. Here the repo scope has no row of its
     * own while global is drifted -- a fallback-resolving {@code drift} would inherit global's
     * {@code true}, but a scope with nothing saved has nothing to fork from and must report
     * undrifted, exactly like an uncustomized global scope does.
     */
    @Test
    void driftForAScopeWithNoRowOfItsOwnIsNotCustomizedNotInherited() {
        insertRowWithBase(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}",
                "AN OLDER GLOBAL PERSONA", "Diff:\n{{diff}}");

        PromptRegistry.Drift drift = registry.drift(PromptKind.REVIEW, "acme/widgets");

        assertEquals(true, drift.baseKnown());
        assertEquals(false, drift.defaultDrifted());
    }

    /**
     * Not one of the brief's seven -- added per Ruling 1. Both scopes start drifted with distinct
     * recorded ancestors; accepting the current default for the repo scope must re-stamp only that
     * row. A {@code WHERE kind = ?} update would either clear the wrong scope's drift or miss the
     * intended one entirely -- this fails on either mistake.
     */
    @Test
    void acceptCurrentDefaultOnlyReStampsTheScopeItWasCalledFor() {
        insertRowWithBase(PromptKind.REVIEW, PromptScope.GLOBAL, "Global persona", "Diff:\n{{diff}}",
                "AN OLDER GLOBAL PERSONA", "Diff:\n{{diff}}");
        insertRowWithBase(PromptKind.REVIEW, "acme/widgets", "Repo persona", "Diff:\n{{diff}}",
                "AN OLDER REPO PERSONA", "Diff:\n{{diff}}");

        registry.acceptCurrentDefault(PromptKind.REVIEW, "acme/widgets");

        assertEquals(false, registry.drift(PromptKind.REVIEW, "acme/widgets").defaultDrifted());
        assertEquals(true, registry.drift(PromptKind.REVIEW, PromptScope.GLOBAL).defaultDrifted());
    }

    private void insertRowWithBase(PromptKind kind, String scope, String system, String body,
            String baseSystem, String baseBody) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (scope, kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text       = EXCLUDED.system_text,
                             body_text         = EXCLUDED.body_text,
                             base_system_text  = EXCLUDED.base_system_text,
                             base_body_text    = EXCLUDED.base_body_text,
                             updated_at        = now()
                     """)) {
            ps.setString(1, scope);
            ps.setString(2, kind.slug());
            ps.setString(3, system);
            ps.setString(4, body);
            ps.setString(5, baseSystem);
            ps.setString(6, baseBody);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert row with base for " + kind.slug(), e);
        }
    }

    /**
     * A row as it would exist purely from V23 + V33, before this migration ever ran: no {@code
     * scope} column named in the insert, so it lands on the new column's {@code DEFAULT '*'} exactly
     * as an upgraded deployment's pre-existing rows would.
     */
    private void insertPreMigrationRow(PromptKind kind, String system, String body) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template (kind, system_text, body_text, updated_at)
                     VALUES (?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text = EXCLUDED.system_text,
                             body_text   = EXCLUDED.body_text,
                             updated_at  = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert pre-migration row for " + kind.slug(), e);
        }
    }
}
