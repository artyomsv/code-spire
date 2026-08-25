package dev.codespire.context.code;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetExtractorTest {

    private static final String FILE = """
            package dev.example.pricing;

            /** Prices one call at the rate in force when it happened. */
            public long chargeFor(TokenCount tokens, Rate rate) {
                long a = 1;
                long b = 2;
                long c = 3;
                return a + b + c;
            }
            """;

    @Test
    void theDeclarationAndItsDocCommentAreIncluded() {
        String snippet = SnippetExtractor.extract(FILE, "chargeFor", 40);

        assertTrue(snippet.contains("public long chargeFor(TokenCount tokens, Rate rate)"));
        assertTrue(snippet.contains("Prices one call at the rate in force"));
    }

    @Test
    void theSignatureSurvivesEvenWhenTheBodyIsClippedToNothing() {
        String snippet = SnippetExtractor.extract(FILE, "chargeFor", 1);

        // The high-value information lives in the signature and doc, so clipping must never
        // reach them — a snippet clipped to its signature is still useful; one clipped past it
        // is worse than absent, because it costs prompt budget and teaches nothing.
        assertTrue(snippet.contains("public long chargeFor(TokenCount tokens, Rate rate)"));
        assertTrue(snippet.contains("...(truncated to fit the model context)"));
    }

    @Test
    void aSymbolNotDeclaredInTheTextIsNull() {
        assertNull(SnippetExtractor.extract(FILE, "someOtherThing", 40));
    }
}
