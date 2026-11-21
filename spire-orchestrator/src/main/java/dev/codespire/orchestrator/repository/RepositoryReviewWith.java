package dev.codespire.orchestrator.repository;

import dev.codespire.contract.llm.HarnessPolicy;
import dev.codespire.orchestrator.factory.FactoryConfig;
import dev.codespire.orchestrator.factory.HarnessCatalogues;
import dev.codespire.orchestrator.llm.LlmDefaultModel;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.llm.LlmProviderView;
import dev.codespire.orchestrator.repository.RepositoryRegistry.ReviewLlmChoice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * A repository's "Review with" choice, checked before any write so a mistake is the caller's (400), not a key or CHECK
 * violation: an API provider with its model, or a harness, or neither. Moved out of {@link RepositoryRegistry}, which
 * keeps the reads and writes.
 */
@ApplicationScoped
public class RepositoryReviewWith {
    @Inject LlmProviderRegistry llmProviders;
    @Inject LlmDefaultModel llmChoice;
    @Inject FactoryConfig factoryConfig;
    @Inject HarnessCatalogues catalogues;

    /** What the repository holds now: its API pair (or {@link ReviewLlmChoice#NONE}) and its harness (or null). */
    record Stored(ReviewLlmChoice choice, ReviewHarness harness) {
        /** A new repository: nothing is stored, so every choice is new. */
        static final Stored NONE = new Stored(ReviewLlmChoice.NONE, null);
    }

    /** Every rule of the choice, the offer last so a structural mistake is named first. */
    void require(RepositoryInput input, Stored stored) {
        requireReviewProvider(input);
        requireReviewHarness(input);
        requireReviewModel(input, stored.choice());
        requireHarnessOffer(input.reviewHarness(), stored.harness());
    }

    /**
     * A NEW harness choice names a model the harness runs, and a thinking level that model offers: the check a build
     * setup's save makes ({@link HarnessCatalogues#refusal}), so a choice that saves is one a review run can admit. A
     * harness choice saved before is sent back unchanged by every save of another setting and must keep saving, as
     * the API pair does; the run's own admission still refuses it.
     */
    private void requireHarnessOffer(ReviewHarness harness, ReviewHarness stored) {
        if (harness == null || harness.equals(stored)) return;
        catalogues.refusal(harness.harness(), harness.model(), harness.effort()).ifPresent(reason -> {
            throw new IllegalArgumentException("Review with: " + reason + " — choose a model and thinking level the "
                    + harness.harness() + " harness offers");
        });
    }

    /** Looked up before any write, so an id naming no provider is the caller's mistake (400), not a key violation. */
    private void requireReviewProvider(RepositoryInput input) {
        UUID provider = input.reviewLlmProviderId();
        if (provider != null && llmProviders.get(provider).isEmpty()) {
            throw new IllegalArgumentException("LLM provider " + provider + " does not exist; choose one listed in Settings → LLM");
        }
    }

    /**
     * One "Review with" choice, checked before any write so a mistake is the caller's (400), not a CHECK violation:
     * a harness the deployment has an agent image for (the key a build setup is checked against, never the worker's
     * HarnessRegistry), a model, and no API provider beside it (V109).
     */
    private void requireReviewHarness(RepositoryInput input) {
        ReviewHarness harness = input.reviewHarness();
        if (harness == null) return;
        if (input.reviewLlmProviderId() != null) {
            throw new IllegalArgumentException("Review with: choose an API provider or a harness, not both");
        }
        // Before the image is looked up: the refusal is about the harness, whatever this deployment configures.
        // NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
        // through a Claude subscription or to hold claude.ai tokens:
        // https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
        if (harness.subscription() && !HarnessPolicy.allowsSubscription(harness.harness())) {
            throw new IllegalArgumentException("Review with: " + HarnessPolicy.SUBSCRIPTION_NOT_ALLOWED + " — the "
                    + harness.harness() + " harness pays with an API key only");
        }
        if (harness.harness() == null || !factoryConfig.configuredAgentImages().containsKey(harness.harness())) {
            throw new IllegalArgumentException("Review with: unknown harness " + harness.harness()
                    + "; choose one with an agent image (spire.factory.agent-image.<harness>)");
        }
        if (harness.model() == null) throw new IllegalArgumentException("Review with: a harness review needs a model");
    }

    /**
     * The API choice is whole: a provider and its model together, or neither (the global default); none beside a
     * harness; and a new choice is a model of the provider's vendor that the ledger can price. Checked before any
     * write, so a mistake is the caller's (400), not a CHECK violation. A PUT naming a provider with no model (an older
     * dashboard) is refused rather than silently clearing the repository's model.
     *
     * <p>The vendor and pricing rule applies to a NEW choice only (spec sections 3 and 12): a pair saved before, such
     * as a gateway pair V118 copied, is sent back unchanged by every save of another setting and must keep saving.
     * The runtime path and the attention rows report such a pair.
     */
    private void requireReviewModel(RepositoryInput input, ReviewLlmChoice stored) {
        UUID provider = input.reviewLlmProviderId();
        String model = input.reviewLlmModel();
        if (input.reviewHarness() != null) {
            if (model != null) throw new IllegalArgumentException("Review with: a harness review takes its model from the harness; send no reviewLlmModel");
            return;
        }
        if (provider == null && model == null) return;
        if (provider == null || model == null) {
            throw new IllegalArgumentException("Review with: choose an LLM provider and its model together, or neither to use the default");
        }
        if (stored.equals(new ReviewLlmChoice(provider, model))) return;
        String type = llmProviders.get(provider).map(LlmProviderView::type).orElseThrow(
                () -> new IllegalArgumentException("LLM provider " + provider + " does not exist; choose one listed in Settings → LLM"));
        try {
            llmChoice.requireChoice(type, model);
        } catch (LlmDefaultModel.ChoiceRefused refused) {
            throw new IllegalArgumentException("Review with: " + refused.getMessage());
        }
    }
}
