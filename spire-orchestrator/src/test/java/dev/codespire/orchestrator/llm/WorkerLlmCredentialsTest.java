package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which LLM provider and model a review spends: the provider and model its repository names, else the default
 * provider with the default review model ({@code llm.default-model}). A named provider that cannot be spent refuses;
 * it never falls back, so the payer never changes without the operator choosing it.
 *
 * <p>The repository's choice is written with SQL rather than through its registry, so this suite tests the resolution
 * alone; the default provider and model are set together, as the dashboard sets them.
 */
@QuarkusTest
class WorkerLlmCredentialsTest {

    @Inject WorkerLlmCredentials credentials;
    @Inject LlmProviderRegistry providers;
    @Inject LlmDefaultModel defaultModels;
    @Inject LlmModelRegistry models;
    @Inject RepositoryRegistry repositories;
    @Inject ReviewProjection reviews;
    @Inject DataSource dataSource;
    @Inject ReviewDiffLimit diffLimit;
    @Inject AppSettingRepository settings;
    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;

    private final List<UUID> createdProviders = new ArrayList<>();
    private String workspace;
    private String defaultModel;
    private String namedModel;

    @BeforeEach
    void defaultProvider() throws Exception {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        workspace = "TEST-llm-" + suffix;
        defaultModel = catalogued("TEST-default-model-" + suffix, true);
        namedModel = catalogued("TEST-named-model-" + suffix, true);
        UUID defaultId = provider("TEST-default-" + suffix, "openai", true);
        defaultModels.setDefaultProvider(defaultId, defaultModel);
    }

    /**
     * The repository row and the review row go first, because the repository names a provider and a
     * provider a repository names cannot be deleted; then this suite's providers and the default model, so the next
     * suite's reset finds nothing of ours.
     */
    @AfterEach
    void removeFixture() throws Exception {
        execute("DELETE FROM review_status WHERE workspace=?", workspace);
        execute("DELETE FROM repository_registration_bridge WHERE target=?", workspace + "/TEST-repo");
        execute("DELETE FROM repository_account WHERE repository_id IN (SELECT id FROM repository WHERE workspace=?)", workspace);
        execute("DELETE FROM repository WHERE workspace=?", workspace);
        for (UUID id : createdProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
    }

    @Test
    void aRepositoryProviderUsesTheRepositoryModel() throws Exception {
        UUID named = provider("TEST-named", "openai", true);
        String reviewId = reviewOn(repository(named, namedModel));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), "an enabled, priceable named choice is spendable: " + llm.detail());
        assertEquals(namedModel, llm.model(), "the review must spend the model its repository names");
    }

    @Test
    void theDefaultProviderUsesTheDefaultModel() throws Exception {
        String reviewId = reviewOn(repository(null, null));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), llm.detail());
        assertEquals(defaultModel, llm.model(), "a repository naming no provider spends the default review model");
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
    void noDefaultModelIsRefusedNoDefaultModel() throws Exception {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String reviewId = reviewOn(repository(null, null));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertEquals(DefaultLlm.Refusal.NO_DEFAULT_MODEL, llm.refusal(),
                "a default provider without a default model must refuse, not spend some other model");
        assertEquals("No default review model is selected. Choose one in Settings → LLM.", llm.note());
    }

    @Test
    void aSwitchedOffNamedProviderRefusesRatherThanFallingBack() throws Exception {
        UUID named = provider("TEST-switched-off", "openai", false);
        String reviewId = reviewOn(repository(named, namedModel));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertEquals(DefaultLlm.Refusal.REPOSITORY_PROVIDER_OFF, llm.refusal(),
                "a switched-off named provider must refuse, never fall back to the default");
        assertTrue(llm.note().contains("TEST-switched-off"), "the note names the provider: " + llm.note());
    }

    /** The priceability guard applies to a repository's model exactly as to the default. */
    @Test
    void anUnpriceableModelIsStillRefused() throws Exception {
        UUID named = provider("TEST-unpriceable", "openai", true);
        String rateless = "TEST-uncatalogued-" + UUID.randomUUID().toString().substring(0, 8);
        String reviewId = reviewOn(repository(named, rateless));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertEquals(DefaultLlm.Refusal.MODEL_NOT_PRICEABLE, llm.refusal());
        assertEquals(rateless, llm.model(), "the refusal names the repository's model, not the default's");
    }

    /** A gateway setup: an anthropic-type provider in front of an openai model runs, as it did before the split. */
    @Test
    void aVendorMismatchStillRuns() throws Exception {
        UUID named = provider("TEST-gateway", "anthropic", true);
        String reviewId = reviewOn(repository(named, namedModel));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), "a vendor mismatch is a warning, not a refusal: " + llm.detail());
        assertEquals(namedModel, llm.model());
    }

    /** A switched-off model keeps running for API reviews (pricing ignores enabled); attention asks for another. */
    @Test
    void aSwitchedOffChosenModelStillRuns() throws Exception {
        String off = catalogued("TEST-off-model-" + UUID.randomUUID().toString().substring(0, 8), false);
        UUID named = provider("TEST-named-off-model", "openai", true);
        String reviewId = reviewOn(repository(named, off));

        DefaultLlm llm = credentials.resolveForReview(reviewId);

        assertTrue(llm.isSpendable(), "a switched-off chosen model still runs: " + llm.detail());
        assertEquals(off, llm.model());
    }

    /**
     * Review input limit: the Review diff limit rides the packed credential, and a manual model, which has no window
     * in the catalog, packs none (the worker keeps today's budgets).
     */
    @Test
    void theReviewDiffLimitRidesTheCredential() throws Exception {
        diffLimit.set(90_000);
        try {
            DefaultLlm llm = credentials.resolveForReview(reviewOn(repository(null, null)));

            LlmCredential packed = mapper.readValue(
                    encryption.decryptString(llm.packed(), LlmCredential.aad(workspace)), LlmCredential.class);
            assertEquals(90_000, packed.diffTokenCap());
            assertNull(packed.maxInputTokens(), "a manual model has no context window");
        } finally {
            diffLimit.set(null);
        }
    }

    /**
     * PR 456, code-quality/M-5: an invalid stored limit packs the lowest limit, never none and never a refusal. With a
     * cap of 24,000 every kind keeps today's budget, by the worker's floor (DiffBudgetTest:
     * theSettingNeverLowersTheBudgetUnderTheDefault,
     * theSettingAtTheReviewMinimumLeavesAReconcileAtItsOwnDefaultOrMore).
     */
    @Test
    void anInvalidStoredLimitPacksTheLowestLimit() throws Exception {
        settings.set(ReviewDiffLimit.KEY, "TEST-abc");
        try {
            DefaultLlm llm = credentials.resolveForReview(reviewOn(repository(null, null)));

            assertTrue(llm.isSpendable(), "an invalid limit never blocks a review");
            LlmCredential packed = mapper.readValue(
                    encryption.decryptString(llm.packed(), LlmCredential.aad(workspace)), LlmCredential.class);
            assertEquals(ReviewDiffLimit.MIN_TOKENS, packed.diffTokenCap());
        } finally {
            diffLimit.set(null);
        }
    }

    // ---- fixture -------------------------------------------------------------

    private String catalogued(String name, boolean enabled) {
        models.create(new LlmModelInput("openai", name, name, "UNMETERED", Map.of(),
                null, null, null, null, enabled, List.of()));
        return name;
    }

    private UUID provider(String name, String type, boolean enabled) {
        UUID id = UUID.fromString(providers.create(new LlmProviderInput(name, type,
                "https://llm.example.invalid", "TEST-key", null, null, enabled, false, null)).id());
        createdProviders.add(id);
        return id;
    }

    private UUID repository(UUID reviewProvider, String reviewModel) throws Exception {
        UUID id = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                workspace, "TEST-repo", true, null, null)).id();
        if (reviewProvider != null) {
            execute("UPDATE repository SET review_llm_provider_id=?, review_llm_model=? WHERE id=?",
                    reviewProvider, reviewModel, id);
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
