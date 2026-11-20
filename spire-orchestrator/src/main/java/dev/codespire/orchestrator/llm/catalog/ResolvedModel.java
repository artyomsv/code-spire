package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.contract.review.TokenType;
import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.orchestrator.llm.ModelRate;
import dev.codespire.orchestrator.llm.PricingMode;

import java.util.Map;

/**
 * Everything the app asks about one model name, from whichever source won. {@code catalogEntry} is the
 * catalog's full entry for a CATALOG answer and null for a MANUAL one.
 */
public record ResolvedModel(String name, String type, String label, ModelSource source, PricingMode pricingMode,
        Map<TokenType, ModelRate> rates, boolean enabled, ModelParamProfile profile, CatalogModel catalogEntry) {

    public ResolvedModel {
        rates = rates == null ? Map.of() : Map.copyOf(rates);
    }
}
