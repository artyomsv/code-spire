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

    // A formatter commonly wraps a long parameter list across several continuation lines once it
    // exceeds the print width — the whole span is still one signature, not "body," and must survive
    // clipping in full. Wrapped across three lines (not two) so a narrower fix that only protects
    // one extra continuation line cannot coincidentally pass this test by luck of maxBodyLines.
    private static final String WRAPPED_SIGNATURE_FILE =
            "public long chargeFor(\n"
                    + "        TokenCount tokens,\n"
                    + "        Rate rate) {\n"
                    + "    return 1;\n"
                    + "}\n";

    @Test
    void theFullSignatureSurvivesWhenItIsWrappedAcrossLines() {
        String snippet = SnippetExtractor.extract(WRAPPED_SIGNATURE_FILE, "chargeFor", 1);

        assertTrue(snippet.contains(
                "public long chargeFor(\n        TokenCount tokens,\n        Rate rate) {"));
        assertTrue(snippet.contains("...(truncated to fit the model context)"));
    }

    @Test
    void anUnterminatedBodyIsReportedAsTruncated() {
        String neverCloses = "public long chargeFor(TokenCount tokens, Rate rate) {\n    long a = 1;\n";

        String snippet = SnippetExtractor.extract(neverCloses, "chargeFor", 40);

        assertTrue(snippet.contains("...(truncated to fit the model context)"));
    }

    private static final String TS_FILE = """
            export function helper(x: number): number {
                return x + 1;
            }

            export const compute = (x: number): number => {
                return x * 2;
            };

            export interface Config {
                enabled: boolean;
            }

            export type Mode = "active" | "observe";
            """;

    @Test
    void aTypeScriptFunctionDeclarationIsFound() {
        String snippet = SnippetExtractor.extract(TS_FILE, "helper", 40);

        assertTrue(snippet.contains("export function helper(x: number): number {"));
    }

    @Test
    void aTypeScriptExportedArrowFunctionIsFound() {
        String snippet = SnippetExtractor.extract(TS_FILE, "compute", 40);

        assertTrue(snippet.contains("export const compute = (x: number): number => {"));
    }

    @Test
    void aTypeScriptInterfaceDeclarationIsFound() {
        String snippet = SnippetExtractor.extract(TS_FILE, "Config", 40);

        assertTrue(snippet.contains("export interface Config {"));
    }

    @Test
    void aTypeScriptTypeDeclarationIsFound() {
        String snippet = SnippetExtractor.extract(TS_FILE, "Mode", 40);

        assertTrue(snippet.contains("export type Mode = \"active\" | \"observe\";"));
    }
}
