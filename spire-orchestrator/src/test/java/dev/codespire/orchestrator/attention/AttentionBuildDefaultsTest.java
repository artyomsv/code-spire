package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository whose saved build setup cannot price what its harness reports (M3.5 part D).
 *
 * <p>The refusal happens at dispatch, correctly — but by then somebody has written a ticket, applied a
 * label and approved a plan, and the item stops for a rate nobody was ever asked for. That is what
 * happened to work item 36. This row says it before any of that.
 */
@QuarkusTest
@TestSecurity(user = "test-viewer", roles = "spire-viewer")
class AttentionBuildDefaultsTest {

    @Inject AttentionQueries queries;
    @Inject RepositoryRegistry repositories;
    @Inject ProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    private UUID repository(String suffix) {
        UUID account = UUID.fromString(providers.create(new ProviderInput("TEST-attention-" + suffix, "github",
                "https://github.example.invalid", "bearer", null, "TEST-token", "TEST-bot", true,
                List.of(), "TEST-bot", null, "REVIEWER")).id());
        return repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                "TEST-attention", "TEST-repo-" + suffix, true, account, null)).id();
    }

    private void saveSetup(UUID repository, String harness, String model) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO repository_build_defaults(repository_id,base_branch,harness,model,updated_by)
                     VALUES (?,'main',?,?,'TEST-operator')
                     ON CONFLICT (repository_id) DO UPDATE SET harness=excluded.harness,model=excluded.model
                     """)) {
            ps.setObject(1, repository); ps.setString(2, harness); ps.setString(3, model);
            ps.executeUpdate();
        }
    }

    /**
     * The rows this suite writes must not outlive it. The panel is deployment-wide, and another suite
     * that empties the model catalogue would find these setups pointing at a model that no longer
     * exists — an unpriceable repository nobody configured, on a panel that asserts it is empty.
     */
    @AfterEach
    void removeTheSetupsThisSuiteWrote() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM repository_build_defaults WHERE repository_id IN
                       (SELECT id FROM repository WHERE workspace = 'TEST-attention')
                     """)) {
            ps.executeUpdate();
        }
    }

    private List<AttentionView> rowsFor(String repository) {
        return queries.collect().stream()
                .filter(row -> "BUILD_MODEL_NOT_PRICEABLE".equals(row.code()) && repository.equals(row.subject())).toList();
    }

    @Test
    void aRepositoryWhoseBuildModelCannotPriceWhatItsHarnessReportsIsNamed() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID repository = repository(suffix);
        String model = "TEST-attention-model-" + suffix;
        models.create(new LlmModelInput("openai", model, "TEST attention model", "METERED",
                Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), null, null, null, Map.of(), true, List.of()));
        saveSetup(repository, "codex", model);

        List<AttentionView> rows = rowsFor("TEST-attention/TEST-repo-" + suffix);
        assertEquals(1, rows.size(), "one row for this repository");
        assertEquals(AttentionView.Severity.WARNING, rows.getFirst().severity());
        assertTrue(rows.getFirst().message().contains("CACHED_INPUT"), rows.getFirst().message());
        assertTrue(rows.getFirst().message().contains("REASONING"), rows.getFirst().message());
        assertEquals("/settings/llm", rows.getFirst().action());
    }

    /** The discriminating half: the same model, priced for everything codex reports, raises nothing. */
    @Test
    void aCompleteSetupRaisesNothing() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID repository = repository(suffix);
        String model = "TEST-attention-complete-" + suffix;
        models.create(new LlmModelInput("openai", model, "TEST complete model", "METERED",
                Map.of("INPUT", 200_000L, "OUTPUT", 400_000L, "CACHED_INPUT", 50_000L),
                null, null, null, Map.of(), true, List.of("CACHE_WRITE", "REASONING")));
        saveSetup(repository, "codex", model);

        assertEquals(List.of(), rowsFor("TEST-attention/TEST-repo-" + suffix));
    }

    /** A repository switched off is not one whose next build is waiting on a rate. */
    @Test
    void aDisabledRepositoryIsNotNagged() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID repository = repository(suffix);
        String model = "TEST-attention-disabled-" + suffix;
        models.create(new LlmModelInput("openai", model, "TEST disabled repo model", "METERED",
                Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), null, null, null, Map.of(), true, List.of()));
        saveSetup(repository, "codex", model);
        var view = repositories.get(repository).orElseThrow();
        repositories.update(repository, view.revision(), new RepositoryInput("github", "https://github.example.invalid",
                "TEST-attention", "TEST-repo-" + suffix, false, view.reviewer() == null ? null : view.reviewer().id(), null));

        assertEquals(List.of(), rowsFor("TEST-attention/TEST-repo-" + suffix));
    }
}
