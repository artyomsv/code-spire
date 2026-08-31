package dev.codespire.llm;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingsParserTest {

    private static final ModelUsage USAGE = ModelUsage.of("test-model", 10, 5);

    @Test
    void parsesCleanJson() {
        ReviewResult result = parse("""
                { "summary": "Looks solid overall.",
                  "findings": [
                    { "path": "src/App.java", "line": 11, "endLine": 12,
                      "severity": "MAJOR", "message": "NPE risk", "suggestion": "use Optional" }
                  ] }
                """, USAGE);
        assertEquals("Looks solid overall.", result.summary());
        assertEquals(1, result.findings().size());
        var finding = result.findings().getFirst();
        assertEquals("src/App.java", finding.path());
        assertEquals(11, finding.range().startLine());
        assertEquals(12, finding.range().endLine());
        assertEquals(Severity.MAJOR, finding.severity());
        assertEquals("use Optional", finding.suggestion());
    }

    /**
     * The wire-up between the model's JSON and the closed enum (P4 / ADR-027).
     *
     * <p>Both ends were tested and the join between them was not: the enum has its own tests and
     * the database column has its own, but nothing proved the parser reads the field at all.
     */
    @Test
    void readsTheCategoryTheModelWasAskedFor() {
        ReviewResult result = parse("""
                { "summary": "s",
                  "findings": [
                    { "path": "src/App.java", "line": 3, "severity": "NIT",
                      "category": "NAMING", "message": "m", "suggestion": null }
                  ] }
                """, USAGE);
        assertEquals(FindingCategory.NAMING, result.findings().getFirst().category());
    }

    /**
     * A model that omits the field, or invents an eleventh label, leaves the category UNKNOWN —
     * never {@code OTHER}. {@code OTHER} is an answer the model gave; an unparseable label means
     * nobody knows what it meant, and grouping the two together would count confusions as a kind.
     * A customized review prompt (E16) produces the omitted case on every finding it parses.
     */
    @Test
    void anAbsentOrUnrecognisedCategoryParsesToNullRatherThanOther() {
        ReviewResult omitted = parse("""
                { "summary": "s",
                  "findings": [
                    { "path": "src/App.java", "line": 3, "severity": "NIT", "message": "m" }
                  ] }
                """, USAGE);
        assertNull(omitted.findings().getFirst().category());

        ReviewResult invented = parse("""
                { "summary": "s",
                  "findings": [
                    { "path": "src/App.java", "line": 3, "severity": "NIT",
                      "category": "ARCHITECTURE", "message": "m" }
                  ] }
                """, USAGE);
        assertNull(invented.findings().getFirst().category());
    }

    @Test
    void stripsMarkdownFences() {
        ReviewResult result = parse("""
                ```json
                { "summary": "ok", "findings": [] }
                ```
                """, USAGE);
        assertEquals("ok", result.summary());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void extractsJsonFromChatter() {
        ReviewResult result = parse(
                "Sure! Here is the review: { \"summary\": \"fine\", \"findings\": [] } Hope it helps!",
                USAGE);
        assertEquals("fine", result.summary());
    }

    @Test
    void toleratesTrailingCommasAndSingleQuotes() {
        ReviewResult result = parse("""
                { 'summary': 'ok', 'findings': [
                    { 'path': 'a.java', 'line': 3, 'severity': 'NIT', 'message': 'm', 'suggestion': null, },
                  ],
                }
                """, USAGE);
        assertEquals(1, result.findings().size());
        assertNull(result.findings().getFirst().suggestion());
    }

    @Test
    void degradesGracefullyOnGarbage() {
        ReviewResult result = parse("I could not produce JSON, sorry.", USAGE);
        assertTrue(result.findings().isEmpty());
        // A parse failure must be visibly marked so it is never mistaken for a clean
        // review, while still carrying the model's raw output.
        assertTrue(result.summary().startsWith(FindingsParser.DEGRADED_PREFIX), result.summary());
        assertTrue(result.summary().contains("I could not produce JSON, sorry."));
    }

    @Test
    void cleanEmptyFindingsIsNotMarkedDegraded() {
        // Valid JSON with no findings is a genuine clean pass — it must keep its real
        // summary and NOT get the degraded marker (the false-negative guard).
        ReviewResult result = parse("{ \"summary\": \"No issues found.\", \"findings\": [] }", USAGE);
        assertTrue(result.findings().isEmpty());
        assertEquals("No issues found.", result.summary());
    }

    @Test
    void degradedSummaryIsBounded() {
        String ramble = "no json here, just endless prose. ".repeat(3_000); // ~100k chars
        ReviewResult result = parse(ramble, USAGE);
        assertTrue(result.findings().isEmpty());
        assertTrue(result.summary().length() <= 4_100 + FindingsParser.DEGRADED_PREFIX.length(),
                "degraded summary (marker + clipped raw) must stay bounded");
        assertTrue(result.summary().endsWith("...(truncated to fit the model context)"));
    }

    @Test
    void dropsUnanchorableFindings() {
        ReviewResult result = parse("""
                { "summary": "s", "findings": [
                    { "path": "", "line": 5, "severity": "INFO", "message": "no path" },
                    { "path": "a.java", "line": 0, "severity": "INFO", "message": "bad line" },
                    { "path": "a.java", "line": 7, "severity": "WHATEVER", "message": "kept, severity coerced" }
                ] }
                """, USAGE);
        assertEquals(1, result.findings().size());
        assertEquals(Severity.INFO, result.findings().getFirst().severity());
    }

    @Test
    void fencedSuggestionInsideFencedOutputStillParses() {
        // Review finding L2 regression: a ``` fence INSIDE a suggestion value
        // must not truncate the outer JSON extraction.
        ReviewResult result = parse("""
                ```json
                { "summary": "ok", "findings": [
                    { "path": "a.java", "line": 3, "severity": "MINOR", "message": "m",
                      "suggestion": "```\\nfixed()\\n```" } ] }
                ```
                """, USAGE);
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().getFirst().suggestion().contains("fixed()"));
    }

    @Test
    void endLineNeverPrecedesStartLine() {
        ReviewResult result = parse("""
                { "summary": "s", "findings": [
                    { "path": "a.java", "line": 9, "endLine": 3, "severity": "INFO", "message": "m" } ] }
                """, USAGE);
        assertEquals(9, result.findings().getFirst().range().endLine());
    }

    @Test
    void aParsedResponseIsNotDegraded() {
        ReviewResult result = parse("""
                { "summary": "all good", "findings": [] }
                """, USAGE);
        assertFalse(result.degraded(), "a clean review with nothing to report is not degraded");
    }

    @Test
    void anEmptyResponseIsDegraded() {
        // The case that shipped: the model spent its whole output budget and returned nothing. Zero
        // findings is also what a clean review reports, so without this flag the two are the same row.
        ReviewResult result = parse("", USAGE);
        assertTrue(result.degraded());
        assertEquals(0, result.findings().size());
    }

    @Test
    void aNullResponseIsDegraded() {
        assertTrue(parse(null, USAGE).degraded());
    }

    @Test
    void anUnparseableRambleIsDegradedToo() {
        // Also zero findings, and also not a pass: the model said something, none of it structured.
        ReviewResult result = parse("I would rather talk about something else.", USAGE);
        assertTrue(result.degraded());
        assertTrue(result.summary().startsWith(FindingsParser.DEGRADED_PREFIX));
    }

    @Test
    void degradedSurvivesBeingMarkedTruncated() {
        // ReviewWorker re-marks a clipped diff on the way out; a rebuild that dropped the flag there
        // would restore the exact silence this fixes.
        assertTrue(parse("", USAGE).withTruncated(true).degraded());
    }
    /** The suite's default: a response the provider finished on its own terms. */
    private static ReviewResult parse(String modelOutput, ModelUsage usage) {
        return FindingsParser.parse(new Completion(modelOutput, usage));
    }

    /** A response the provider stopped at its output limit. */
    private static ReviewResult parseCapped(String modelOutput) {
        return FindingsParser.parse(new Completion(modelOutput, USAGE, true));
    }

    @Test
    void aResponseCutOffAtItsOutputLimitIsDegradedEvenWhenItParses() {
        // The residual case, and the one raising the output cap makes MORE likely rather than less:
        // a model with room to start answering is cut off part-way rather than before it begins. The
        // prefix still parses, so nothing about the JSON says it is incomplete — only the provider
        // does, and it says so in the finish reason.
        ReviewResult result = parseCapped("""
                { "summary": "s", "findings": [
                    { "path": "a.java", "line": 3, "severity": "MAJOR", "message": "real finding" } ] }
                """);
        assertEquals(1, result.findings().size(), "the findings it did produce are kept");
        assertTrue(result.degraded(), "a partial finding set must not read as a finished review");
    }

    @Test
    void anUncappedResponseThatParsesIsNotDegraded() {
        ReviewResult result = FindingsParser.parse(new Completion("""
                { "summary": "s", "findings": [
                    { "path": "a.java", "line": 3, "severity": "MAJOR", "message": "real finding" } ] }
                """, USAGE, false));
        assertFalse(result.degraded());
    }
}
