package dev.codespire.llmcatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat model. {@code modelId} is the name a harness or the review path reports; every alias is
 * another name the same model is reported under.
 */
public record CatalogModel(String modelId, String provider, String displayName, List<String> aliases,
        CatalogLifecycle lifecycle, Long contextWindow, Long maxOutputTokens, CatalogCapabilities capabilities,
        CatalogApiDialect api, CatalogPricing pricing) {

    public CatalogModel {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    /** A retired model is disabled for new runs but stays priced, so a run that started before is charged. */
    public boolean isRetired() {
        return lifecycle != null && lifecycle.status() == CatalogLifecycle.Status.RETIRED;
    }

    /** Every name this model answers to: the model id first, then the aliases in file order. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        names.add(modelId);
        names.addAll(aliases);
        return List.copyOf(names);
    }
}
