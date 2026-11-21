package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.factory.FactoryConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.util.UUID;

/** The repository fixture plus TEST- LLM providers, each with a priced model of its own, for "Review with" tests. */
abstract class RepositoryReviewWithFixture extends RepositoryFixture {
    @Inject LlmProviderRegistry llmProviders;
    @Inject LlmModelRegistry llmModels;
    @Inject FactoryConfig factoryConfig;

    /** The harness the review-harness tests choose, by the name the deployment configures it under. */
    static final String CODEX = "codex";
    final java.util.Set<UUID> createdLlmProviders = new java.util.HashSet<>();

    final java.util.Map<UUID, String> modelOf = new java.util.HashMap<>();

    /** An openai provider and a priced openai model chosen with it. */
    UUID llmProvider(String name) {
        String model = "TEST-repository-model-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("openai", model, model, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        UUID id = UUID.fromString(llmProviders.create(new LlmProviderInput("TEST-" + name, "openai",
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
        createdLlmProviders.add(id);
        modelOf.put(id, model);
        return id;
    }

    /** The provider with its own model, or neither (the global default); an unknown provider has no model. */
    RepositoryInput repositoryReviewingWith(UUID provider) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, provider)
                .withReviewLlm(provider, provider == null ? null : modelOf.getOrDefault(provider, "TEST-repository-unknown"));
    }

    /** Runs before the fixture removes the repository, so the provider is unnamed first; a named one cannot go. */
    @AfterEach void removeLlmProviders() throws Exception {
        execute("UPDATE repository SET review_llm_provider_id=NULL, review_llm_model=NULL WHERE workspace=?", workspace);
        for (UUID id : createdLlmProviders) execute("DELETE FROM llm_provider WHERE id=?", id);
    }

    RepositoryInput repositoryReviewingOn(ReviewHarness harness) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, null, harness);
    }

    /** A priced anthropic model: known and enabled, so only the vendor check can refuse it beside an openai provider. */
    String anthropicModel(String name) {
        String model = "TEST-repository-anthropic-" + name + "-" + UUID.randomUUID();
        llmModels.create(new LlmModelInput("anthropic", model, model, "UNMETERED", java.util.Map.of(),
                null, null, null, null, true, java.util.List.of()));
        return model;
    }

    /**
     * What the codex image runs, as its run worker would report it: TEST-model at low, medium and high, and
     * TEST-other-model with no levels. A harness choice is checked against this list when it is saved.
     */
    @BeforeEach void codexOffersTestModels() throws Exception {
        execute("""
                INSERT INTO harness_catalogue (harness, image, status, models, observed_at, pinned_image, asked_at)
                VALUES (?, ?, 'OK', CAST(? AS jsonb), now(), NULL, now())
                ON CONFLICT (harness) DO UPDATE SET image = excluded.image, status = excluded.status,
                       models = excluded.models, pinned_image = excluded.pinned_image""",
                CODEX, factoryConfig.configuredAgentImages().get(CODEX), CODEX_MODELS);
    }

    private static final String CODEX_MODELS = "[{\"slug\":\"TEST-model\",\"displayName\":\"TEST-model\","
            + "\"defaultEffort\":\"medium\",\"efforts\":[\"low\",\"medium\",\"high\"],\"visible\":true,\"priority\":1},"
            + "{\"slug\":\"TEST-other-model\",\"displayName\":\"TEST-other-model\",\"defaultEffort\":null,"
            + "\"efforts\":[],\"visible\":true,\"priority\":2}]";

    @AfterEach void removeCodexOffer() throws Exception {
        execute("DELETE FROM harness_catalogue WHERE harness=?", CODEX);
    }
}
