package dev.codespire.llmcatalog;

/** What a model accepts, as the vendor's model page states it. */
public record CatalogCapabilities(boolean tools, boolean structuredOutput, boolean imageInput, boolean promptCache) {
}
