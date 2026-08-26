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
 *
 * <p>That protected span is itself bounded two ways, so it can never grow into <em>more</em> prompt
 * budget than an unclipped body would have cost in the first place: it stops at the first blank
 * line (a declaration is never separated from its own continuation lines by one, in either
 * supported language, so a blank line marks its true end — including a bodyless single-expression
 * arrow function, whose "signature" is the whole declaration), and it gives up after
 * {@link #MAX_SIGNATURE_SCAN_LINES} lines without finding a terminator. Either bound falls back to
 * protecting only the regex-matched declaration line itself, discarding whatever else was
 * tentatively scanned — losing a wrapped signature that is this ambiguous is safer than silently
 * annexing an unrelated declaration or a whole file's worth of content into one symbol's snippet.
 */
public final class SnippetExtractor {

    // The blank-line bound above is the primary guard against annexing unrelated content — it
    // fires on ordinary files long before this cap would matter. This cap is only a backstop for a
    // file with no blank line to stop at (or a malformed/truncated fetch), so it can afford to be
    // generous: measured against this repository's own longest real declarations of the record
    // shape the class javadoc calls out — from `public record X(` to the line opening `{` — with
    // ReviewDetail (37 lines) the worst case found. 40 covers that with headroom without being
    // large enough to matter for genuinely pathological input.
    private static final int MAX_SIGNATURE_SCAN_LINES = 40;

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
        return extract(fileContent.split("\\R"), symbol, maxBodyLines);
    }

    /**
     * Same as {@link #extract(String, String, int)}, but takes a file already split into lines — a
     * caller resolving several symbols against one file (see {@code CodeContextProvider}'s
     * {@code extractCandidates}) splits it exactly once and reuses the array, rather than
     * re-splitting the whole file per symbol (M7, rung-1 final review).
     */
    public static String extract(String[] lines, String symbol, int maxBodyLines) {
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
        return scanBody(lines, signature.nextIndex(), signature.braceDepth(),
                signature.sawOpenBrace(), maxBodyLines, out);
    }

    /**
     * Extends the declaration line across any wrapped continuation lines (a long parameter list
     * split across lines, as a formatter commonly does) up to and including the line that opens the
     * body ({@code {}) or terminates the statement ({@code ;}). Every line in this span is added
     * unconditionally — this is the part of the snippet that must never be clipped.
     *
     * <p>Bounded by a blank line and by {@link #MAX_SIGNATURE_SCAN_LINES}: neither the tentative
     * span accumulated so far, nor anything beyond it, is added to {@code out} when either bound is
     * hit without finding a terminator — {@link #protectDeclarationLineOnly} takes over instead.
     */
    private static SignatureSpan scanSignature(String[] lines, int declarationLine, List<String> out) {
        int scanLimit = Math.min(lines.length, declarationLine + MAX_SIGNATURE_SCAN_LINES);
        List<String> span = new ArrayList<>();
        int braceDepth = 0;

        for (int i = declarationLine; i < scanLimit; i++) {
            String line = lines[i];
            if (i > declarationLine && line.isBlank()) {
                break;
            }
            span.add(line);
            braceDepth = updatedDepth(braceDepth, line);
            if (line.indexOf('{') >= 0) {
                out.addAll(span);
                return new SignatureSpan(i + 1, braceDepth, true, braceDepth <= 0);
            }
            if (line.trim().endsWith(";")) {
                out.addAll(span);
                return new SignatureSpan(i + 1, braceDepth, false, true);
            }
        }
        return protectDeclarationLineOnly(lines, declarationLine, out);
    }

    /**
     * The fallback for a signature the two bounds above refused to extend across: protects only the
     * regex-matched declaration line itself, exactly as the pre-fix implementation always did.
     */
    private static SignatureSpan protectDeclarationLineOnly(String[] lines, int declarationLine,
            List<String> out) {
        String declaration = lines[declarationLine];
        out.add(declaration);
        int braceDepth = updatedDepth(0, declaration);
        boolean sawOpenBrace = declaration.indexOf('{') >= 0;
        boolean complete = sawOpenBrace ? braceDepth <= 0 : declaration.trim().endsWith(";");
        return new SignatureSpan(declarationLine + 1, braceDepth, sawOpenBrace, complete);
    }

    /**
     * Takes lines forward from where the signature span left off, tracking brace depth, until the
     * depth returns to zero (the declaration's body closed naturally) or {@code maxBodyLines} is
     * reached — whichever comes first.
     *
     * <p>The blank-line stop only applies while {@code sawOpenBrace} is still false — i.e. only on
     * the ambiguous hand-off from {@link #protectDeclarationLineOnly}, where no body has actually
     * been found yet and a blank line is the same "declaration has ended" signal
     * {@link #scanSignature} honors. Once a real opening brace has been seen (the ordinary case,
     * and also the fallback case where the declaration line itself already opened one), we are
     * inside a genuine, brace-delimited body, and a blank line there is just routine style — for
     * example a locally-scoped variable followed by a blank line before the return — not a signal
     * to stop. Reported as {@code false} either way: neither is a clipped-for-budget truncation.
     *
     * @return whether the body was cut short of its natural close
     */
    private static boolean scanBody(String[] lines, int start, int braceDepth,
            boolean sawOpenBrace, int maxBodyLines, List<String> out) {
        int cap = Math.max(0, maxBodyLines);
        int taken = 0;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (!sawOpenBrace && line.isBlank()) {
                return false;
            }
            if (taken >= cap) {
                return true;
            }
            out.add(line);
            taken++;
            braceDepth = updatedDepth(braceDepth, line);
            if (line.indexOf('{') >= 0) {
                sawOpenBrace = true;
            }
            if (sawOpenBrace && braceDepth <= 0) {
                return false;
            }
            if (!sawOpenBrace && line.trim().endsWith(";")) {
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
     * @param nextIndex    the line index immediately after the signature span
     * @param braceDepth   the brace depth accumulated by the end of the signature span
     * @param sawOpenBrace whether an opening brace has been seen anywhere in the span so far —
     *                     {@code scanBody} must not treat a depth of zero as "closed" before one has
     *                     actually been opened
     * @param complete     whether the declaration (and, if the whole body fit on the signature span,
     *                     its body too) is already fully captured — no further body scan is needed
     */
    private record SignatureSpan(int nextIndex, int braceDepth, boolean sawOpenBrace, boolean complete) {
    }
}
