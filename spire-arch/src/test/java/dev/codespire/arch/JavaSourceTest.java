package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The comment stripper decides what every architecture check can see, so its failure
 * mode is the dangerous one: strip too much and the checks pass on a codebase full of
 * violations, reporting a green build that means nothing. These tests exist so that
 * silent-pass mode cannot happen unnoticed.
 */
class JavaSourceTest {

    private static String stripped(String src) {
        return JavaSource.withoutComments(src);
    }

    @Test
    void aLineCommentGoesAwayAndTheCodeBesideItStays() {
        String out = stripped("int x = 1; // gitlab only\n");
        assertTrue(out.contains("int x = 1;"));
        assertFalse(out.contains("gitlab"));
    }

    @Test
    void aBlockCommentGoesAwayWithoutMovingAnyLineNumber() {
        String src = """
                class A {
                    /* gitlab
                       bitbucket */
                    int keep = 2;
                }
                """;
        String out = stripped(src);
        assertFalse(out.contains("gitlab"));
        assertFalse(out.contains("bitbucket"));
        // Line 4 must still be line 4, or every reported location is off by the size
        // of the comments above it.
        assertEquals("    int keep = 2;", out.split("\n", -1)[3]);
        assertEquals(src.length(), out.length(), "positions must be preserved, not shifted");
    }

    @Test
    void javadocGoesAwayToo() {
        String out = stripped("""
                /** Explains why GitLab needs no special case. */
                class A {}
                """);
        assertFalse(out.contains("GitLab"));
        assertTrue(out.contains("class A {}"));
    }

    @Test
    void aStringLiteralIsKeptBecauseThatIsWhereTheDecisionsHide() {
        // The leak this whole check exists for is a string comparison, not a type reference.
        String out = stripped("if (\"bitbucket-cloud\".equals(type)) {}");
        assertTrue(out.contains("\"bitbucket-cloud\""));
    }

    @Test
    void aTextBlockIsKeptAndItsBareQuotesDoNotEndIt() {
        String out = stripped("""
                String q = \"""
                        { "type": "gitlab" }
                        \""";
                int after = 1;
                """);
        assertTrue(out.contains("\"gitlab\""), "text-block content is code, not commentary");
        assertTrue(out.contains("int after = 1;"), "a bare quote inside must not swallow the rest");
    }

    @Test
    void slashesInsideAStringAreNotACommentStart() {
        // A URL is the everyday case: mis-parsing it as a comment would blank the rest
        // of the line and hide anything after it.
        String out = stripped("String url = \"https://example.invalid/x\"; int keep = 3;");
        assertTrue(out.contains("https://example.invalid/x"));
        assertTrue(out.contains("int keep = 3;"));
    }

    @Test
    void aQuoteCharLiteralDoesNotOpenAString() {
        String out = stripped("char q = '\"'; // gitlab\nint keep = 4;");
        assertFalse(out.contains("gitlab"), "the char literal closed, so the comment was still a comment");
        assertTrue(out.contains("int keep = 4;"));
    }

    @Test
    void anEscapedQuoteDoesNotEndTheString() {
        String out = stripped("String s = \"a\\\"b\"; // github\nint keep = 5;");
        assertFalse(out.contains("github"));
        assertTrue(out.contains("int keep = 5;"));
    }

    @Test
    void anUnterminatedBlockCommentDoesNotOverrunTheBuffer() {
        // Malformed input must fail as a normal result, not an exception that would
        // take the whole check down.
        assertFalse(stripped("int x = 1; /* gitlab").contains("gitlab"));
    }
}
