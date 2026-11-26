package dev.codespire.orchestrator.llm;

import dev.codespire.contract.llm.ModelParams;
import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The two input limits a credential carries to the worker (review input limit design, 2026-11-26): how much input the
 * model takes, from the catalog's context window, and the operator's Review diff limit. Kept out of
 * {@link LlmModelRegistry} and {@link WorkerLlmCredentials}, which already held more than a reader can.
 *
 * <p>The two unknowns fail in opposite directions on purpose. An unknown window (a manual model, a catalog read fault)
 * keeps today's fixed budgets, the small answer. A Review diff limit the database cannot read fails the pack instead of
 * reading as unset, because unset is the large answer: a database fault must never send an uncapped review (PR 456);
 * the review saga schedules a retry. An invalid stored limit reads as the lowest one, today's budgets.
 */
@ApplicationScoped
public class ReviewInputLimits {

    private static final Logger LOG = Logger.getLogger(ReviewInputLimits.class);

    @Inject
    LlmModelCatalog catalog;

    @Inject
    ReviewDiffLimit diffLimit;

    /** The limits for one credential; either is null when unknown (the window) or unset (the setting). */
    public record Limits(Integer maxInputTokens, Integer diffTokenCap) {
    }

    /**
     * @param model             the model the credential names
     * @param providerMaxTokens the output cap the call sends, or null for {@link ModelParams#DEFAULT_MAX_OUTPUT_TOKENS}
     * @throws dev.codespire.orchestrator.settings.AppSettingRepository.ReadFailed when the setting cannot be read
     */
    public Limits forModel(String model, Integer providerMaxTokens) {
        Integer cap = diffLimit.current().orElse(null);
        return new Limits(maxInputTokens(contextWindow(model).orElse(null), providerMaxTokens), cap);
    }

    /**
     * The model's context window in tokens, from the catalog. Empty for a manual model (it has no window), a name
     * nobody has, and a read fault: unknown means the review keeps today's fixed diff budgets, never a larger one.
     */
    Optional<Long> contextWindow(String model) {
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        try {
            return catalog.resolve(model).map(ResolvedModel::catalogEntry).map(CatalogModel::contextWindow);
        } catch (LlmModelCatalog.CatalogueReadFailed e) {
            LOG.warnf(e, "context window lookup failed for model %s", model);
            return Optional.empty();
        }
    }

    /**
     * How much input a call may send: the model's context window minus the output cap the call really sends (the
     * provider's max tokens, else {@link ModelParams#DEFAULT_MAX_OUTPUT_TOKENS}). The catalog's own output limit is
     * not used, since the call does not send it. Null when the window is unknown, which keeps today's budgets; long
     * arithmetic, clamped into an int, never below zero (no room refuses the review in the worker, by name).
     */
    static Integer maxInputTokens(Long contextWindow, Integer providerMaxTokens) {
        if (contextWindow == null) {
            return null;
        }
        long reserve = providerMaxTokens != null ? providerMaxTokens : ModelParams.DEFAULT_MAX_OUTPUT_TOKENS;
        long input = contextWindow - reserve;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, input));
    }
}
