package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.llm.catalog.CatalogTestDoubles;
import dev.codespire.orchestrator.llm.catalog.LlmCatalogState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog's own attention text and LLM_CATALOG_STALE, on hand-built collaborators and a fixed clock. */
class LlmCatalogAttentionTest {

    private static final String REMOTE = "https://example.invalid/catalog.yaml";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private static LlmCatalogAttention attention(String url, LlmCatalogState row, String interval) {
        CatalogTestDoubles.FakeStates states = new CatalogTestDoubles.FakeStates().with(row);
        LlmCatalogAttention attention = new LlmCatalogAttention();
        attention.catalog = CatalogTestDoubles.catalogAt(url, states);
        attention.states = states;
        attention.refreshInterval = interval;
        attention.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return attention;
    }

    private static LlmCatalogState lastSuccess(Instant at, String lastError) {
        return new LlmCatalogState(null, null, null, "remote", REMOTE, at, at, at, lastError, null);
    }

    private static List<AttentionView> staleRows(LlmCatalogAttention attention) {
        List<AttentionView> rows = new ArrayList<>();
        attention.staleRow(rows);
        return rows;
    }

    @Test
    void aStaleCatalogRaisesAWarning() {
        LlmCatalogAttention stale = attention(REMOTE, lastSuccess(NOW.minus(Duration.ofHours(19)), "http_503"), "6h");

        List<AttentionView> rows = staleRows(stale);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("LLM_CATALOG_STALE", rows.getFirst().code());
        assertEquals(AttentionView.Severity.WARNING, rows.getFirst().severity());
        assertTrue(rows.getFirst().message().contains(REMOTE), rows.getFirst().message());
        assertTrue(rows.getFirst().message().contains("http_503"), rows.getFirst().message());
    }

    @Test
    void aFreshCatalogRaisesNothing() {
        assertEquals(List.of(), staleRows(attention(REMOTE, lastSuccess(NOW.minus(Duration.ofHours(17)), "http_503"),
                "6h")));
    }

    @Test
    void aFileThatFailedValidationRaisesTheRowAtOnce() {
        assertEquals(1, staleRows(attention(REMOTE, lastSuccess(NOW.minus(Duration.ofMinutes(5)),
                "invalid: model TEST-a: pricing.output is required"), "6h")).size());
    }

    @Test
    void withTheIntervalOffTheRowWaitsADay() {
        assertEquals(List.of(), staleRows(attention(REMOTE, lastSuccess(NOW.minus(Duration.ofHours(23)), null), "off")));
        assertEquals(1, staleRows(attention(REMOTE, lastSuccess(NOW.minus(Duration.ofHours(25)), null), "off")).size());
    }

    @Test
    void nothingStoredYetIsNotStale() {
        LlmCatalogState empty = new LlmCatalogState(null, null, null, null, null, null, null, null, null, null);

        assertEquals(List.of(), staleRows(attention(REMOTE, empty, "6h")));
    }

    @Test
    void urlNoneNeverRaisesStale() {
        assertEquals(List.of(), staleRows(attention("none", lastSuccess(NOW.minus(Duration.ofDays(30)),
                "invalid: TEST"), "6h")));
    }

    @Test
    void aCatalogModelMissingAPriceSaysUpdateTheCatalog() {
        LlmCatalogAttention attention = attention("none", lastSuccess(NOW, null), "6h");

        Optional<String> text = attention.catalogText("TEST-cat-a", List.of(TokenType.REASONING));

        assertEquals(Optional.of("Model TEST-cat-a comes from the catalog and has no price for REASONING. Update the"
                + " catalog, or add a manual model with this name."), text);
    }

    @Test
    void aManualOrUnknownModelKeepsTheSettingsText() {
        LlmCatalogAttention attention = attention("none", lastSuccess(NOW, null), "6h");

        assertEquals(Optional.empty(), attention.catalogText("TEST-not-in-any-catalog", List.of(TokenType.INPUT)));
    }
}
