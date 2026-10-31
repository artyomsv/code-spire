package dev.codespire.orchestrator.llm;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Which LLM provider a review spends (harness reviews, decision 1): the provider its repository names,
 * else the global default. A named provider that cannot be spent refuses; it never falls back, so the
 * payer never changes without the operator choosing it.
 *
 * <p>The repository's choice is written with SQL rather than through {@link RepositoryRegistry}, so this
 * suite tests the resolution alone; the registry's own write is {@code RepositoryResourceTest}'s.
 */
@QuarkusTest
class WorkerLlmCredentialsTest {

    @Inject WorkerLlmCredentials credentials;
    @Inject LlmProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject RepositoryRegistry repositories;
    @Inject ReviewProjection reviews;
    @Inject DataSource dataSource;

    private final List<UUID> createdProviders = new ArrayList<>();
    private String workspace;
    private String defaultModel;
    private String namedModel;

    @BeforeEach
    void defaultProvider() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        workspace = "TEST-llm-" + suffix;
        defaultModel = catalogued("TEST-default-model-" + suffix);
        namedModel = catalogued("TEST-named-model-" + suffix);
        UUID defaultId = provider("TEST-default-" + suffix, defaultModel, true);
        providers.setDefault(defaultId);
    }

    /**
     * The repository row and the review row go first, because the repository names a provider and a
     * provider a repository names cannot be deleted; then this suite's providers, so the next suite's
     * reset finds nothing of ours.
     */
    @AfterEach
    void removeFixture() throws Exception {
        execute("DELETE FROM review_status WHERE workspace=?", workspace);
        execute("DELETE FROM repository_registration_bridge WHERE target=?", workspace + "/TEST-repo");
        execute("DELETE FROM repository_account WHERE repository_id IN (SELECT id FROM repository WHERE workspace=?)", workspace);
        execute("DELETE FROM repository WHERE workspace=?", workspace);
        for (UUID id : createdProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
    }

    @Test
    void aReviewSpendsTheProviderItsRepositoryNames() throws Exception {
        UUID named = provider("TEST-named", namedModel, true);
        String reviewId = reviewOn(repository(named));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), "an enabled, priceable named provider is spendable: " + llm.detail());
        assertEquals(namedModel, llm.model(), "the review must spend the provider its repository names");
    }

    @Test
    void aRepositoryThatNamesNoProviderUsesTheGlobalDefault() throws Exception {
        String reviewId = reviewOn(repository(null));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), llm.detail());
        assertEquals(defaultModel, llm.model());
    }

    /** A legacy review row has no repository (V60 left it null), and some reviews have no row yet. */
    @Test
    void aReviewWithoutARepositoryUsesTheGlobalDefault() throws Exception {
        RepoRef repo = new RepoRef(workspace, "TEST-repo");
        String legacy = ReviewIds.reviewId(repo, 7);
        execute("INSERT INTO review_status(review_id,workspace,slug,pr_id,status) VALUES (?,?,?,?,'received')",
                legacy, repo.workspace(), repo.slug(), 7L);

        assertEquals(defaultModel, credentials.resolveForReview(legacy).model(),
                "a review row without a repository keeps the global default");
        assertEquals(defaultModel, credentials.resolveForReview(ReviewIds.reviewId(repo, 8)).model(),
                "a review with no row at all keeps the global default");
    }

    @Test
    void aSwitchedOffNamedProviderRefusesRatherThanFallingBack() throws Exception {
        UUID named = provider("TEST-switched-off", namedModel, false);
        String reviewId = reviewOn(repository(named));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertEquals(DefaultLlm.Refusal.REPOSITORY_PROVIDER_OFF, llm.refusal(),
                "a switched-off named provider must refuse, never fall back to the default");
        assertTrue(llm.note().contains("TEST-switched-off"), "the note names the provider: " + llm.note());
    }

    /** The priceability guard applies to a named provider exactly as to the default. */
    @Test
    void aNamedProviderWithAnUnpriceableModelIsRefused() throws Exception {
        UUID named = provider("TEST-unpriceable", namedModel, true);
        String rateless = "TEST-uncatalogued-" + UUID.randomUUID().toString().substring(0, 8);
        // As V30 does: a provider whose model has no pricing exists without passing the registry guard.
        execute("UPDATE llm_provider SET model=? WHERE id=?", rateless, named);
        String reviewId = reviewOn(repository(named));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertEquals(DefaultLlm.Refusal.MODEL_NOT_PRICEABLE, llm.refusal());
        assertEquals(rateless, llm.model(), "the refusal names the named provider's model, not the default's");
    }

    // ---- fixture -------------------------------------------------------------

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

    private UUID repository(UUID reviewProvider) throws Exception {
        UUID id = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                workspace, "TEST-repo", true, null, null)).id();
        if (reviewProvider != null) {
            execute("UPDATE repository SET review_llm_provider_id=? WHERE id=?", reviewProvider, id);
        }
        return id;
    }

    private String reviewOn(UUID repository) {
        RepoRef repo = new RepoRef(workspace, "TEST-repo");
        String reviewId = ReviewIds.reviewId(repo, 1);
        assertTrue(reviews.claimRepository(reviewId, repository, repo, 1), "the review row names the repository");
        return reviewId;
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) ps.setObject(i + 1, parameters[i]);
            ps.executeUpdate();
        }
    }
}
