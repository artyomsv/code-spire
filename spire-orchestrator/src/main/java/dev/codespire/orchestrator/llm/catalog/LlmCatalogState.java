package dev.codespire.orchestrator.llm.catalog;

import java.time.Instant;

/**
 * The one {@code llm_catalog_state} row: the last good catalog document and how the last fetch went.
 * Every field is null on a new database until the first document is stored.
 */
public record LlmCatalogState(String document, String sha256, String etag, String source, String url,
        Instant loadedAt, Instant lastCheckedAt, Instant lastSuccessAt, String lastError, String lastChangeJson) {

    static final LlmCatalogState EMPTY = new LlmCatalogState(null, null, null, null, null, null, null, null, null,
            null);
}
