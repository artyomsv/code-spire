package dev.codespire.llmcatalog;

/** One vendor: its code is one of {@link Vendors#TYPES}; its prices are read from {@code pricingUrl}. */
public record CatalogProvider(String code, String displayName, String pricingUrl) {
}
