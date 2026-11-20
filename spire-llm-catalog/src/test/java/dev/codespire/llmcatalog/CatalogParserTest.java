package dev.codespire.llmcatalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogParserTest {

    static final String VALID = """
            providers:
              - code: openai
                displayName: TEST provider
                pricingUrl: https://example.invalid/pricing
            models:
              - modelId: TEST-a
                provider: openai
                displayName: TEST model
                aliases: [TEST-a-dated]
                lifecycle:
                  status: ACTIVE
                contextWindow: 1
                maxOutputTokens: 1
                capabilities:
                  tools: true
                api:
                  outputTokenParam: MAX_TOKENS
                  temperature: true
                  reasoningEffort: null
                pricing:
                  input: 1
                  output: 2
                  cacheRead: 3
                  notBilled: [CACHE_WRITE]
                  sourceUrl: https://example.invalid/pricing
                  observedAt: 2026-11-20
            """;

    private static final String WITH_FUTURE_FIELD = VALID.replace("    contextWindow: 1\n",
            "    contextWindow: 1\n    futureField: 1\n");

    @Test
    void aValidFileParsesInBothModes() throws CatalogException {
        CatalogDocument strict = CatalogParser.parse(VALID, CatalogParser.Mode.STRICT);

        assertEquals("TEST-a", strict.models().getFirst().modelId());
        assertEquals(java.util.List.of("TEST-a", "TEST-a-dated"), strict.models().getFirst().names());
        assertEquals(2L, strict.models().getFirst().pricing().output());
    }

    /** A price is a whole number of millicents; 1.9 must be refused, never bound as 1. */
    @Test
    void aFractionalPriceIsRefusedInBothModes() {
        String fractional = VALID.replace("      input: 1", "      input: 1.9");
        assertTrue(fractional.contains("input: 1.9"), "the fixture must carry the fractional price");

        assertThrows(CatalogException.class, () -> CatalogParser.parse(fractional, CatalogParser.Mode.STRICT));
        assertThrows(CatalogException.class, () -> CatalogParser.parse(fractional, CatalogParser.Mode.LENIENT));
    }

    @Test
    void strictRefusesAnUnknownField() {
        assertTrue(WITH_FUTURE_FIELD.contains("futureField"), "the fixture must carry the unknown field");
        assertThrows(CatalogException.class, () -> CatalogParser.parse(WITH_FUTURE_FIELD, CatalogParser.Mode.STRICT));
    }

    @Test
    void lenientIgnoresAnUnknownField() throws CatalogException {
        CatalogDocument doc = CatalogParser.parse(WITH_FUTURE_FIELD, CatalogParser.Mode.LENIENT);

        assertEquals("TEST-a", doc.models().getFirst().modelId());
    }

    @Test
    void refusesYamlAnchorsAndAliases() {
        String anchored = VALID.replace("    capabilities:\n      tools: true\n",
                "    capabilities: &caps\n      tools: true\n") + "extra: *caps\n";
        String anchorOnly = VALID.replace("    capabilities:\n", "    capabilities: &caps\n");

        for (CatalogParser.Mode mode : CatalogParser.Mode.values()) {
            assertThrows(CatalogException.class, () -> CatalogParser.parse(anchored, mode));
            assertThrows(CatalogException.class, () -> CatalogParser.parse(anchorOnly, mode));
        }
    }

    @Test
    void refusesDuplicateKeys() {
        String duplicated = VALID.replace("    provider: openai\n", "    provider: openai\n    modelId: TEST-b\n");

        for (CatalogParser.Mode mode : CatalogParser.Mode.values()) {
            assertThrows(CatalogException.class, () -> CatalogParser.parse(duplicated, mode));
        }
    }

    @Test
    void refusesAFileOverOneMebibyte() {
        String padding = "#".repeat(CatalogLimits.MAX_BYTES + 1 - VALID.length() - 1) + "\n";
        String large = VALID + padding;
        assertEquals(CatalogLimits.MAX_BYTES + 1, large.length());

        CatalogException refused = assertThrows(CatalogException.class,
                () -> CatalogParser.parse(large, CatalogParser.Mode.LENIENT));

        assertTrue(refused.getMessage().contains(String.valueOf(CatalogLimits.MAX_BYTES)), refused.getMessage());
    }

    @Test
    void aFileOfExactlyOneMebibyteIsRead() throws CatalogException {
        String padding = "#".repeat(CatalogLimits.MAX_BYTES - VALID.length() - 1) + "\n";

        CatalogDocument doc = CatalogParser.parse(VALID + padding, CatalogParser.Mode.STRICT);

        assertEquals(1, doc.models().size());
    }

    @Test
    void refusesCustomTags() {
        String tagged = VALID.replace("    displayName: TEST model\n",
                "    displayName: !!java.lang.Object TEST model\n");
        String standardTag = VALID.replace("    displayName: TEST model\n", "    displayName: !!str TEST model\n");

        for (CatalogParser.Mode mode : CatalogParser.Mode.values()) {
            assertThrows(CatalogException.class, () -> CatalogParser.parse(tagged, mode));
            assertThrows(CatalogException.class, () -> CatalogParser.parse(standardTag, mode));
        }
    }

    @Test
    void aRefusalNeverQuotesTheFile() {
        String broken = VALID.replace("    displayName: TEST model\n", "    displayName: [SECRET-TEXT\n");

        CatalogException refused = assertThrows(CatalogException.class,
                () -> CatalogParser.parse(broken, CatalogParser.Mode.LENIENT));

        assertTrue(!refused.getMessage().contains("SECRET-TEXT"), refused.getMessage());
    }
}
