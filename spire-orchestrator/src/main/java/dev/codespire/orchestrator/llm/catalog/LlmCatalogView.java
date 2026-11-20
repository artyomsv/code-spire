package dev.codespire.orchestrator.llm.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * The catalog as Settings shows it: where it came from, how the last read went, what the last accepted
 * file changed, and every catalog entry with a flag when a manual model shadows it.
 *
 * @param url    the configured URL, or null when remote loading is off
 * @param source "bundled" or "remote": where the catalog in use came from
 * @param sha256 the catalog in use
 */
public record LlmCatalogView(String url, String source, String sha256, Instant loadedAt, Instant lastCheckedAt,
        Instant lastSuccessAt, String lastError, JsonNode lastChange, List<LlmModelCatalog.CatalogRow> rows) {
}
