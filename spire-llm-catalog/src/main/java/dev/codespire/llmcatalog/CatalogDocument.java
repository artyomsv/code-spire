package dev.codespire.llmcatalog;

import java.util.List;

/** The whole catalog file: the vendors it prices and their chat models. */
public record CatalogDocument(List<CatalogProvider> providers, List<CatalogModel> models) {

    public CatalogDocument {
        providers = providers == null ? List.of() : List.copyOf(providers);
        models = models == null ? List.of() : List.copyOf(models);
    }
}
