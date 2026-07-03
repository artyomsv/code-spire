package dev.codespire.llm;

import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingsParserTest {

    private static final ModelUsage USAGE = new ModelUsage("test-model", 10, 5, 0);

    @Test
    void parsesCleanJson() {
        ReviewResult result = FindingsParser.parse("""
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

    @Test
    void stripsMarkdownFences() {
        ReviewResult result = FindingsParser.parse("""
                ```json
                { "summary": "ok", "findings": [] }
                ```
                """, USAGE);
        assertEquals("ok", result.summary());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void extractsJsonFromChatter() {
        ReviewResult result = FindingsParser.parse(
                "Sure! Here is the review: { \"summary\": \"fine\", \"findings\": [] } Hope it helps!",
                USAGE);
        assertEquals("fine", result.summary());
    }

    @Test
    void toleratesTrailingCommasAndSingleQuotes() {
        ReviewResult result = FindingsParser.parse("""
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
        ReviewResult result = FindingsParser.parse("I could not produce JSON, sorry.", USAGE);
        assertTrue(result.findings().isEmpty());
        assertEquals("I could not produce JSON, sorry.", result.summary());
    }

    @Test
    void dropsUnanchorableFindings() {
        ReviewResult result = FindingsParser.parse("""
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
        ReviewResult result = FindingsParser.parse("""
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
        ReviewResult result = FindingsParser.parse("""
                { "summary": "s", "findings": [
                    { "path": "a.java", "line": 9, "endLine": 3, "severity": "INFO", "message": "m" } ] }
                """, USAGE);
        assertEquals(9, result.findings().getFirst().range().endLine());
    }
}
