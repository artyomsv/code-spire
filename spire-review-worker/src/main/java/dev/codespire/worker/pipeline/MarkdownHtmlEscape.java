package dev.codespire.worker.pipeline;

/**
 * Escapes raw HTML in model text for a forge comment, leaving Markdown code as written (docs/SECURITY.md, output
 * sanitization).
 *
 * <p>A forge shows code literally, so an escaped {@code <} there reads as {@code &lt;} ({@code List&lt;Double>} on
 * GitHub, spire-test PR 82); everywhere else the escape is what keeps model text from becoming markup. So "code" must
 * mean exactly what a CommonMark renderer treats as code, never what merely looks like it (review of PR 364,
 * security/M-1): a code span opens and closes with backtick runs of the SAME length, within one paragraph, and a
 * backslash before a backtick makes it text; a fenced block (three or more backticks or tildes at a line start, up to
 * three spaces in) is code to its closing fence, or to the end of the text when it has none.
 */
final class MarkdownHtmlEscape {

    private static final char BACKTICK = '`';
    private static final char TILDE = '~';
    private static final char BACKSLASH = '\\';
    private static final int FENCE_MIN = 3;
    private static final int FENCE_MAX_INDENT = 3;

    private MarkdownHtmlEscape() {
    }

    /** {@code text} with every {@code <} outside Markdown code escaped; null is empty. */
    static String outsideCode(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (at < text.length()) {
            at = step(text, at, out);
        }
        return out.toString();
    }

    /** Copies one unit (a fenced block, an escaped character, a code span or one character) and answers where the next starts. */
    private static int step(String text, int at, StringBuilder out) {
        if (atLineStart(text, at)) {
            int fenceEnd = fencedBlockEnd(text, at);
            if (fenceEnd > at) {
                out.append(text, at, fenceEnd);
                return fenceEnd;
            }
        }
        char c = text.charAt(at);
        if (c == BACKSLASH && at + 1 < text.length() && isEscapable(text.charAt(at + 1))) {
            out.append(text, at, at + 2);
            return at + 2;
        }
        if (c == BACKTICK) {
            return codeSpan(text, at, out);
        }
        out.append(c == '<' ? "&lt;" : String.valueOf(c));
        return at + 1;
    }

    /** A backslash escapes a backtick (it is then text) and a backslash (so the next backtick is not escaped). */
    private static boolean isEscapable(char next) {
        return next == BACKTICK || next == BACKSLASH;
    }

    /** A span closed by a run of the same length is copied whole; an unmatched run is text, and scanning goes on after it. */
    private static int codeSpan(String text, int open, StringBuilder out) {
        int afterOpen = runEnd(text, open, BACKTICK);
        int length = afterOpen - open;
        int close = closingRun(text, afterOpen, length);
        int end = close < 0 ? afterOpen : close + length;
        out.append(text, open, end);
        return end;
    }

    /** The start of the next backtick run of exactly {@code length} before a blank line, or -1. */
    private static int closingRun(String text, int from, int length) {
        int at = from;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\n' && blankLineFollows(text, at)) {
                return -1;
            }
            if (c != BACKTICK) {
                at++;
                continue;
            }
            int end = runEnd(text, at, BACKTICK);
            if (end - at == length) {
                return at;
            }
            at = end;
        }
        return -1;
    }

    /** Whether the line after this newline holds only whitespace: a paragraph ends there, and so does any span. */
    private static boolean blankLineFollows(String text, int newline) {
        int at = newline + 1;
        while (at < text.length() && isLineSpace(text.charAt(at))) {
            at++;
        }
        return at >= text.length() || text.charAt(at) == '\n';
    }

    /** The end of the fenced block opening at this line, or -1 when the line opens none. */
    private static int fencedBlockEnd(String text, int lineStart) {
        int fenceAt = afterIndent(text, lineStart);
        if (fenceAt < 0 || fenceAt >= text.length()) {
            return -1;
        }
        char fence = text.charAt(fenceAt);
        if (fence != BACKTICK && fence != TILDE) {
            return -1;
        }
        int length = runEnd(text, fenceAt, fence) - fenceAt;
        int infoEnd = lineEnd(text, fenceAt + length);
        boolean backtickInInfo = fence == BACKTICK && text.substring(fenceAt + length, infoEnd).indexOf(BACKTICK) >= 0;
        if (length < FENCE_MIN || backtickInInfo) {
            return -1;
        }
        return closingFenceEnd(text, nextLine(text, infoEnd), fence, length);
    }

    /** The end of the first line closing a fence of {@code fence} at least {@code length} long, or the end of the text. */
    private static int closingFenceEnd(String text, int from, char fence, int length) {
        int line = from;
        while (line < text.length()) {
            int end = lineEnd(text, line);
            int at = afterIndent(text, line);
            if (at >= 0 && at < end && text.charAt(at) == fence) {
                int run = runEnd(text, at, fence);
                if (run - at >= length && text.substring(run, end).isBlank()) {
                    return end;
                }
            }
            line = nextLine(text, end);
        }
        return text.length();
    }

    /** The first character after up to three spaces of indent, or -1 when the line is indented further. */
    private static int afterIndent(String text, int lineStart) {
        int at = lineStart;
        while (at < text.length() && text.charAt(at) == ' ' && at - lineStart < FENCE_MAX_INDENT) {
            at++;
        }
        return at < text.length() && text.charAt(at) == ' ' ? -1 : at;
    }

    private static int runEnd(String text, int start, char c) {
        int at = start;
        while (at < text.length() && text.charAt(at) == c) {
            at++;
        }
        return at;
    }

    private static int lineEnd(String text, int from) {
        int newline = text.indexOf('\n', from);
        return newline < 0 ? text.length() : newline;
    }

    private static int nextLine(String text, int lineEnd) {
        return Math.min(lineEnd + 1, text.length());
    }

    private static boolean atLineStart(String text, int at) {
        return at == 0 || text.charAt(at - 1) == '\n';
    }

    private static boolean isLineSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r';
    }
}
