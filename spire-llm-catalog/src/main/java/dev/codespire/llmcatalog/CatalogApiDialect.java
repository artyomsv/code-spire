package dev.codespire.llmcatalog;

/**
 * The review path's request dialect. A closed set on purpose: a remote file cannot add free-form
 * parameters to a vendor request or remove the output cap of a paid call.
 */
public record CatalogApiDialect(OutputTokenParam outputTokenParam, boolean temperature, String reasoningEffort) {

    public enum OutputTokenParam { MAX_TOKENS, MAX_COMPLETION_TOKENS }
}
