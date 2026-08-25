package dev.codespire.context.code;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a fetched file's full text plus a symbol name into the snippet a review prompt shows for
 * that symbol: its declaration line, any doc comment immediately above it, and a bounded amount of
 * body.
 *
 * <p>Framework-free by design (see {@code spire-context-code}'s build file) — JDK regex only, no
 * parser dependency, matching {@link JavaLanguageSupport} and {@link TypeScriptLanguageSupport}.
 * The declaration test is deliberately loose across both supported languages: a line is a
 * declaration when it contains the symbol followed by {@code (} or {@code =}, or when the symbol is
 * preceded by {@code class}/{@code interface}/{@code record}/{@code const}/{@code function}/
 * {@code type}. That covers a Java method, a Java or TS class-shaped type, and a TS
 * {@code const}/{@code function}/{@code type} declaration — including an exported arrow function,
 * which is just a {@code const} whose value happens to be a lambda.
 *
 * <p>The declaration line and its immediately preceding comment block always survive clipping,
 * uncounted against {@code maxBodyLines} — the high-value information a reviewer needs (return
 * type, thrown exceptions, nullability, the doc comment's prose) lives there, not in the body. A
 * snippet clipped to its signature is still useful; one clipped past the signature is worse than no
 * snippet at all, since it spends prompt budget without teaching the model anything a bare symbol
 * name didn't already say.
 */
public final class SnippetExtractor {

    // Mirrors dev.codespire.diff.TokenBudget.TRUNCATION_MARKER in spire-diff exactly (three ASCII
    // dots, not an ellipsis character). Not imported: spire-context-code does not depend on
    // spire-diff, so the literal is replicated here rather than pulling in the module for one
    // constant.
    private static final String TRUNCATION_MARKER = "\n...(truncated to fit the model context)";

    private static final Pattern LINE_COMMENT = Pattern.compile("^\\s*//");

    private static final Pattern BLOCK_COMMENT_START = Pattern.compile("^\\s*/\\*");

    private static final Pattern BLOCK_COMMENT_END = Pattern.compile("\\*/\\s*$");

    private SnippetExtractor() {
    }

    /**
     * @return the snippet, or {@code null} when {@code symbol} has no declaration line anywhere in
     *     {@code fileContent} — the normal case for a symbol the file defines under a different name,
     *     or does not define at all, never an error.
     */
    public static String extract(String fileContent, String symbol, int maxBodyLines) {
        String[] lines = fileContent.split("\\R");
        int declarationLine = findDeclarationLine(lines, symbol);
        if (declarationLine < 0) {
            return null;
        }

        int start = leadingCommentStart(lines, declarationLine);

        List<String> snippetLines = new ArrayList<>();
        for (int i = start; i < declarationLine; i++) {
            snippetLines.add(lines[i]);
        }

        boolean truncated = appendBody(lines, declarationLine, maxBodyLines, snippetLines);

        String snippet = String.join("\n", snippetLines);
        return truncated ? snippet + TRUNCATION_MARKER : snippet;
    }

    private static int findDeclarationLine(String[] lines, String symbol) {
        Pattern declaration = declarationPattern(symbol);
        for (int i = 0; i < lines.length; i++) {
            if (declaration.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    private static Pattern declarationPattern(String symbol) {
        String quoted = Pattern.quote(symbol);
        return Pattern.compile(
                "\\b(class|interface|record|const|function|type)\\s+" + quoted + "\\b"
                        + "|\\b" + quoted + "\\b\\s*[(=]");
    }

    /**
     * Walks backwards from the declaration over an immediately preceding comment block — a block
     * comment (opening {@code /*}) or a contiguous run of {@code //} lines — so a doc comment rides
     * along with the declaration it documents. A blank line breaks the walk: only a comment directly
     * attached to the declaration counts.
     */
    private static int leadingCommentStart(String[] lines, int declarationLine) {
        int i = declarationLine - 1;
        if (i < 0) {
            return declarationLine;
        }
        String previous = lines[i].trim();
        if (BLOCK_COMMENT_END.matcher(previous).find()) {
            while (i >= 0) {
                if (BLOCK_COMMENT_START.matcher(lines[i]).find()) {
                    return i;
                }
                i--;
            }
            return 0;
        }
        if (LINE_COMMENT.matcher(previous).find()) {
            while (i >= 0 && LINE_COMMENT.matcher(lines[i]).find()) {
                i--;
            }
            return i + 1;
        }
        return declarationLine;
    }

    /**
     * Appends lines from {@code declarationLine} onward, tracking brace depth so the body stops at
     * its natural close, until either the depth returns to zero or {@code maxBodyLines} is reached.
     * The declaration line itself always counts as the first line of that budget and is never
     * dropped — even a budget of zero or less still yields the signature alone, clipped immediately
     * after.
     *
     * @return whether the body was cut short of its natural close
     */
    private static boolean appendBody(String[] lines, int declarationLine, int maxBodyLines,
            List<String> out) {
        int cap = Math.max(1, maxBodyLines);
        int taken = 0;
        int braceDepth = 0;
        boolean sawOpenBrace = false;

        for (int i = declarationLine; i < lines.length; i++) {
            if (taken >= cap) {
                return true;
            }
            String line = lines[i];
            out.add(line);
            taken++;

            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    braceDepth++;
                    sawOpenBrace = true;
                } else if (ch == '}') {
                    braceDepth--;
                }
            }

            if (sawOpenBrace && braceDepth <= 0) {
                return false;
            }
            if (!sawOpenBrace && line.trim().endsWith(";")) {
                return false;
            }
        }
        return false;
    }
}
