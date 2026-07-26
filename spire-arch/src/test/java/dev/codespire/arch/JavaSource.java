package dev.codespire.arch;

/**
 * A Java source file with its comments blanked out, so an architecture check can look
 * at code only. Character positions and line breaks are preserved, so a match's line
 * number still points at the right line of the file on disk.
 *
 * <p>String literals are deliberately KEPT. A provider name in shared code is most
 * often a string — {@code "bitbucket-cloud".equals(type)} — and those comparisons are
 * exactly the provider-dependent decisions worth failing a build over. (It is also why
 * these checks read source text rather than bytecode: a string literal is invisible to
 * a bytecode-level rule.)
 *
 * <p>Comments are dropped because a comment cannot make a decision. Explaining why a
 * provider-neutral design exists — "GitLab's discussion id differs from its note id" —
 * is knowledge worth keeping, not a leak.
 */
final class JavaSource {

    private JavaSource() {
    }

    /** The source with every comment replaced by spaces; same length, same line breaks. */
    static String withoutComments(String src) {
        char[] out = src.toCharArray();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i = blankBlockComment(src, out, i);
            } else if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                i = skipTextBlock(src, i);
            } else if (c == '"' || c == '\'') {
                i = skipQuoted(src, i, c);
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Blanks {@code /* ... *}{@code /}, keeping newlines so line numbers stay aligned. */
    private static int blankBlockComment(String src, char[] out, int start) {
        int n = src.length();
        int i = start;
        out[i++] = ' ';
        out[i++] = ' ';
        while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
            if (src.charAt(i) != '\n' && src.charAt(i) != '\r') {
                out[i] = ' ';
            }
            i++;
        }
        // Blank the closing */ — guarded, since an unterminated comment must not overrun.
        for (int closing = 0; closing < 2 && i < n; closing++) {
            out[i++] = ' ';
        }
        return i;
    }

    /** Index just past a {@code "..."} or {@code '...'} literal, honouring backslash escapes. */
    private static int skipQuoted(String src, int start, char quote) {
        int i = start + 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            // A newline inside a single-quoted literal means the source is malformed;
            // stop there rather than swallowing the rest of the file.
            if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** Index just past a text block; only {@code """} closes it, so a bare quote is content. */
    private static int skipTextBlock(String src, int start) {
        int i = start + 3;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"' && i + 2 < src.length() && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                return i + 3;
            }
            i++;
        }
        return i;
    }
}
