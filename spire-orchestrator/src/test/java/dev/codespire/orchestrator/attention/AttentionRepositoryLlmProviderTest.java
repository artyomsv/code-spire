package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository whose named review provider cannot be spent (harness reviews, decision 1).
 *
 * <p>Its reviews are refused rather than paid from the default, so the panel must say so before a pull
 * request arrives, on the repository's row: each repository chooses its own provider.
 */
@QuarkusTest
@TestSecurity(user = "test-viewer", roles = "spire-viewer")
class AttentionRepositoryLlmProviderTest {

    static final String PROVIDER_OFF = "REPOSITORY_LLM_PROVIDER_OFF";
    static final String MODEL_NOT_PRICEABLE = "REPOSITORY_LLM_MODEL_NOT_PRICEABLE";
    private static final String WORKSPACE = "TEST-attention-llm";

    @Inject AttentionQueries queries;
    @Inject RepositoryRegistry repositories;
    @Inject LlmProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    private final List<UUID> createdProviders = new ArrayList<>();

    /** The repositories go first: a provider a repository names cannot be deleted. */
    @AfterEach
    void removeFixture() throws Exception {
        execute("UPDATE repository SET review_llm_provider_id=NULL WHERE workspace=?", WORKSPACE);
        execute("DELETE FROM repository_account WHERE repository_id IN (SELECT id FROM repository WHERE workspace=?)", WORKSPACE);
        execute("DELETE FROM repository WHERE workspace=?", WORKSPACE);
        for (UUID id : createdProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
    }

    @Test
    void aRepositoryWhoseNamedProviderIsSwitchedOffIsBlocking() throws Exception {
        String suffix = suffix();
        UUID provider = provider("TEST-off-" + suffix, catalogued("TEST-attention-off-" + suffix), false);
        String repository = repositoryNaming(provider, suffix);

        List<AttentionView> rows = rowsFor(PROVIDER_OFF, repository);
        assertEquals(1, rows.size(), "one row for this repository: " + queries.collect());
        assertEquals(AttentionView.Severity.BLOCKING, rows.getFirst().severity());
        assertTrue(rows.getFirst().message().contains("TEST-off-" + suffix), rows.getFirst().message());
    }

    @Test
    void aRepositoryWhoseNamedProviderCannotBePricedIsNamed() throws Exception {
        String suffix = suffix();
        UUID provider = provider("TEST-rateless-" + suffix, catalogued("TEST-attention-priced-" + suffix), true);
        String rateless = "TEST-attention-uncatalogued-" + suffix;
        // As V30 does: a provider whose model has no pricing exists without passing the registry guard.
        execute("UPDATE llm_provider SET model=? WHERE id=?", rateless, provider);
        String repository = repositoryNaming(provider, suffix);

        List<AttentionView> rows = rowsFor(MODEL_NOT_PRICEABLE, repository);
        assertEquals(1, rows.size(), "one row for this repository: " + queries.collect());
        assertTrue(rows.getFirst().message().contains(rateless), rows.getFirst().message());
    }

    /** The discriminating half: an enabled, priceable named provider raises neither row. */
    @Test
    void aSpendableNamedProviderRaisesNothing() throws Exception {
        String suffix = suffix();
        UUID provider = provider("TEST-fine-" + suffix, catalogued("TEST-attention-fine-" + suffix), true);
        String repository = repositoryNaming(provider, suffix);

        assertEquals(List.of(), rowsFor(PROVIDER_OFF, repository));
        assertEquals(List.of(), rowsFor(MODEL_NOT_PRICEABLE, repository));
    }

    private List<AttentionView> rowsFor(String code, String repository) {
        return queries.collect().stream()
                .filter(row -> code.equals(row.code()) && repository.equals(row.subject())).toList();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String catalogued(String name) {
        models.create(new LlmModelInput("openai", name, name, "UNMETERED", Map.of(),
                null, null, null, null, true, List.of()));
        return name;
    }

    private UUID provider(String name, String model, boolean enabled) {
        UUID id = UUID.fromString(providers.create(new LlmProviderInput(name, "openai",
                "https://llm.example.invalid", "TEST-key", model, null, null, enabled, false)).id());
        createdProviders.add(id);
        return id;
    }

    /** Returns the repository's subject on the panel, {@code workspace/slug}. */
    private String repositoryNaming(UUID provider, String suffix) throws Exception {
        String slug = "TEST-repo-" + suffix;
        UUID id = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                WORKSPACE, slug, true, null, null)).id();
        execute("UPDATE repository SET review_llm_provider_id=? WHERE id=?", provider, id);
        return WORKSPACE + "/" + slug;
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) ps.setObject(i + 1, parameters[i]);
            ps.executeUpdate();
        }
    }
}
