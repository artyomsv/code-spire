package dev.codespire.orchestrator.readmodel;

import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import dev.codespire.orchestrator.llm.catalog.ResolvedModel;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * The LLM vendor badge of a review's model, manual models first, then the catalog; null when nobody has the model, when
 * there is no model yet, or when the manual tables cannot be read (a badge is not worth failing the reviews list over).
 * Each model is asked once per instance, so one list read asks once per distinct model, not once per row.
 */
final class VendorBadges {

    private static final Logger LOG = Logger.getLogger(VendorBadges.class);

    private final LlmModelCatalog catalog;
    private final Map<String, String> answered = new HashMap<>();

    VendorBadges(LlmModelCatalog catalog) {
        this.catalog = catalog;
    }

    String typeOf(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        // containsKey, not computeIfAbsent: an unknown model or a fault answers null, and null is an answer too.
        if (!answered.containsKey(model)) {
            answered.put(model, resolve(model));
        }
        return answered.get(model);
    }

    private String resolve(String model) {
        try {
            return catalog.resolve(model).map(ResolvedModel::type).orElse(null);
        } catch (LlmModelCatalog.CatalogueReadFailed unreadable) {
            LOG.debugf("vendor badge unknown for model %s: the model tables could not be read", model);
            return null;
        }
    }
}
