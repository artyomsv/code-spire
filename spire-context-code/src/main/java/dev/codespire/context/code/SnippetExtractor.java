package dev.codespire.context.code;

import dev.codespire.contract.llm.PromptClipping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a fetched file's full text plus a symbol name into the snippet a review prompt shows for
 * that symbol: its declaration, any doc comment immediately above it, and a bounded amount of body.
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
 * <p>The declaration's full signature — extended across any wrapped continuation lines through the
 * line that opens the body ({@code {}) or terminates the statement ({@code ;}) — and its
 * immediately preceding comment block always survive clipping, uncounted against
 * {@code maxBodyLines}. The high-value information a reviewer needs (return type, thrown
 * exceptions, nullability, the doc comment's prose) lives there, not in the body, and a long
 * parameter list wrapped onto several lines is still one signature, not "body." A snippet clipped
 * to its signature is still useful; one clipped mid-signature is worse than no snippet at all,
 * since it spends prompt budget without teaching the model anything a bare symbol name didn't
 * already say.
 */
public final class SnippetExtractor {

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
        return truncated ? snippet + PromptClipping.TRUNCATION_MARKER : snippet;
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
     * Adds the declaration's full signature span first — protected, uncounted against
     * {@code maxBodyLines} — then, if a body follows the signature's opening brace, adds body lines
     * charged against that budget.
     *
     * @return whether the snippet was cut short of the declaration's natural close
     */
    private static boolean appendBody(String[] lines, int declarationLine, int maxBodyLines,
            List<String> out) {
        SignatureSpan signature = scanSignature(lines, declarationLine, out);
        if (signature.complete()) {
            return false;
        }
        return scanBody(lines, signature.nextIndex(), signature.braceDepth(), maxBodyLines, out);
    }

    /**
     * Extends the declaration line across any wrapped continuation lines (a long parameter list
     * split across lines, as a formatter commonly does) up to and including the line that opens the
     * body ({@code {}) or terminates the statement ({@code ;}). Every line in this span is added
     * unconditionally — this is the part of the snippet that must never be clipped.
     */
    private static SignatureSpan scanSignature(String[] lines, int declarationLine, List<String> out) {
        int braceDepth = 0;
        for (int i = declarationLine; i < lines.length; i++) {
            String line = lines[i];
            out.add(line);
            braceDepth = updatedDepth(braceDepth, line);

            if (line.indexOf('{') >= 0) {
                return new SignatureSpan(i + 1, braceDepth, braceDepth <= 0);
            }
            if (line.trim().endsWith(";")) {
                return new SignatureSpan(i + 1, braceDepth, true);
            }
        }
        // Ran out of file while still inside a wrapped signature — every remaining line is already
        // in out, and the empty range handed to scanBody below reports this as truncated, since the
        // input ended before the declaration reached its natural close.
        return new SignatureSpan(lines.length, braceDepth, false);
    }

    /**
     * Takes lines forward from where the signature span left off, tracking brace depth, until either
     * the depth returns to zero (the declaration's body closed naturally) or {@code maxBodyLines} is
     * reached — whichever comes first.
     *
     * @return whether the body was cut short of its natural close
     */
    private static boolean scanBody(String[] lines, int start, int braceDepth, int maxBodyLines,
            List<String> out) {
        int cap = Math.max(0, maxBodyLines);
        int taken = 0;
        for (int i = start; i < lines.length; i++) {
            if (taken >= cap) {
                return true;
            }
            String line = lines[i];
            out.add(line);
            taken++;
            braceDepth = updatedDepth(braceDepth, line);
            if (braceDepth <= 0) {
                return false;
            }
        }
        return true; // ran out of file before the body closed
    }

    private static int updatedDepth(int braceDepth, String line) {
        for (int c = 0; c < line.length(); c++) {
            char ch = line.charAt(c);
            if (ch == '{') {
                braceDepth++;
            } else if (ch == '}') {
                braceDepth--;
            }
        }
        return braceDepth;
    }

    /**
     * @param nextIndex  the line index immediately after the signature span
     * @param braceDepth the brace depth accumulated by the end of the signature span
     * @param complete   whether the declaration (and, if the whole body fit on the signature span,
     *                   its body too) is already fully captured — no further body scan is needed
     */
    private record SignatureSpan(int nextIndex, int braceDepth, boolean complete) {
    }
}
