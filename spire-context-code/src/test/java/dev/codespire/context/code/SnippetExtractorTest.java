package dev.codespire.context.code;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // The re-reviewer's exact case: a no-semicolon TypeScript style where `identity`'s
    // single-expression arrow body has no braces, so its own declaration line has no `{`/`;`
    // terminator anywhere on it — the very next non-blank thing forward is `helper`'s unrelated
    // declaration, which must never be annexed into `identity`'s snippet.
    private static final String NO_SEMICOLON_STYLE_FILE =
            "export const identity = (x: number) => x\n"
                    + "\n"
                    + "export function helper(y: number): number {\n"
                    + "    return y\n"
                    + "}\n";

    @Test
    void aBlankLineEndsTheDeclarationInsteadOfAnnexingTheNextOne() {
        String snippet = SnippetExtractor.extract(NO_SEMICOLON_STYLE_FILE, "identity", 40);

        assertTrue(snippet.contains("export const identity = (x: number) => x"));
        assertFalse(snippet.contains("helper"));
    }

    // A pathologically long, never-terminating parameter list — more continuation lines than
    // MAX_SIGNATURE_SCAN_LINES (40) before the real opening brace. The scan must give up and fall
    // back to the declaration line alone rather than sweep everything up to that distant brace in
    // as one "free," uncounted signature span. Built rather than hand-written so the line count
    // stays visibly and reliably above the cap even if the cap is retuned again later.
    private static final String PATHOLOGICALLY_LONG_SIGNATURE_FILE = pathologicallyLongSignatureFile();

    private static String pathologicallyLongSignatureFile() {
        StringBuilder file = new StringBuilder("public long chargeFor(\n");
        for (int i = 0; i < 45; i++) {
            file.append("        Param").append(i).append(" p").append(i).append(",\n");
        }
        file.append("        Rate rate) {\n")
                .append("    return 1;\n")
                .append("}\n");
        return file.toString();
    }

    @Test
    void aScanExceedingTheCapFallsBackToTheDeclarationLineAlone() {
        String snippet = SnippetExtractor.extract(PATHOLOGICALLY_LONG_SIGNATURE_FILE, "chargeFor", 1);

        assertTrue(snippet.contains("public long chargeFor("));
        assertFalse(snippet.contains("Rate rate) {"));
        assertTrue(snippet.contains("...(truncated to fit the model context)"));
    }

    // The Critical this round exists for: scanBody's blank-line stop must not fire once a real,
    // brace-delimited body has actually been found — an internal blank line (routine style, used
    // constantly in this codebase) is not a signal that the declaration has ended.
    private static final String BODY_WITH_INTERNAL_BLANK_LINE =
            "public void foo() {\n"
                    + "    int a = 1;\n"
                    + "\n"
                    + "    return a;\n"
                    + "}\n";

    @Test
    void aBlankLineInsideARealBodySurvivesIntact() {
        String snippet = SnippetExtractor.extract(BODY_WITH_INTERNAL_BLANK_LINE, "foo", 40);

        assertTrue(snippet.contains("int a = 1;\n\n    return a;\n}"));
        assertFalse(snippet.contains("...(truncated to fit the model context)"));
    }

    // Re-check the interaction the raised cap invites: a record whose component list itself
    // contains a blank line (unusual formatting, but not impossible). The blank-line bound fires
    // before the (now generous) cap ever gets a chance to matter, and the two bounds must compose
    // sensibly rather than fight — falling back to the declaration line alone, exactly as for the
    // no-semicolon TypeScript case above, not a crash and not a partial, nonsensical merge.
    private static final String RECORD_WITH_INTERNAL_BLANK_FILE =
            "public record ReviewDetail(\n"
                    + "        String id,\n"
                    + "\n"
                    + "        String title) {\n"
                    + "}\n";

    @Test
    void aLongRecordWithAnInternalBlankLineFallsBackSafely() {
        String snippet = SnippetExtractor.extract(RECORD_WITH_INTERNAL_BLANK_FILE, "ReviewDetail", 40);

        assertTrue(snippet.contains("public record ReviewDetail("));
        assertFalse(snippet.contains("String title"));
    }
}
