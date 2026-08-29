package dev.codespire.context.code;

import java.util.Locale;
import java.util.Set;

/**
 * Linear-time text utilities for whole-file symbol scanning.
 *
 * <p>Exists because rung 2 scans <b>whole files</b>, where rung 1 only ever read diff lines, and the
 * regexes written for that first pass do not survive the change of input. Two of them were measured
 * as quadratic on a hostile file: a reluctant {@code /\*.*?\*}{@code /} over a file with no closing
 * delimiter, and a member-declaration pattern whose reluctant span overlapped its own capture group.
 * At 96 KB the first took 21.6s and at 32 KB the second took 28.2s, on a shared four-thread fan-out
 * pool whose 20-second timeout does not interrupt the work. A pull request author chooses that file's
 * contents, so it was a denial of service against the whole deployment's context stage.
 *
 * <p>Everything here is an index scan with no backtracking, so cost is linear in the input and cannot
 * be made otherwise by its contents.
 */
final class SourceText {

    /**
     * Files above this are not scanned at all.
     *
     * <p>A second, independent bound: even linear work over a multi-megabyte generated file is work
     * this feature does not want to do inside a review's context budget, and the index gains little
     * from a file nobody reads. Recall-only, like every other budget here.
     */
    static final int MAX_SCANNED_CHARS = 256 * 1024;

    private SourceText() {
    }

    static boolean tooLargeToScan(String content) {
        return content == null || content.length() > MAX_SCANNED_CHARS;
    }

    /**
     * Removes block and doc comments, replacing each with a space.
     *
     * <p>An unterminated comment consumes the remainder of the file, which is what a compiler would
     * do too.
     */
    static String stripBlockComments(String content) {
        int open = content.indexOf("/*");
        if (open < 0) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length());
        int from = 0;
        while (open >= 0) {
            out.append(content, from, open).append(' ');
            int close = content.indexOf("*/", open + 2);
            if (close < 0) {
                return out.toString();
            }
            from = close + 2;
            open = content.indexOf("/*", from);
        }
        return out.append(content, from, content.length()).toString();
    }

    /**
     * The name of the method or function this line declares, or null.
     *
     * <p>Found by walking back from the first {@code (} rather than by matching a pattern forward,
     * which is both linear and more accurate than the regex it replaces: that one required an
     * explicit {@code public}/{@code protected}/{@code private}, so <b>interface methods and
     * package-private methods were never recorded</b> — and this codebase leans heavily on both, so a
     * change to an interface method would never have triggered a caller lookup for it.
     *
     * @param controlKeywords words that take an argument list without declaring anything
     *     ({@code if}, {@code for}, {@code catch}, …), which is what distinguishes a declaration from
     *     a call or a control-flow construct on an otherwise identical-looking line
     */
    static String declaredCallableName(String line, Set<String> controlKeywords) {
        int paren = line.indexOf('(');
        if (paren <= 0) {
            return null;
        }
        int end = skipWhitespaceBack(line, paren);
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) {
            start--;
        }
        if (start == end || !Character.isJavaIdentifierStart(line.charAt(start))) {
            return null;
        }
        String name = line.substring(start, end);
        if (controlKeywords.contains(name.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return declaresRatherThanCalls(line, start, controlKeywords) ? name : null;
    }

    /**
     * Whether what sits in front of the name makes this line a declaration.
     *
     * <p>Two conditions, and the second is the one that was missing. Something must precede the
     * name — a return type, a modifier, or {@code function} — or the line is a bare call
     * ({@code foo(bar)}). And the PRECEDING WORD must not itself be a control keyword:
     * {@code return chargeFor(rate)} has an identifier in front of the callable and is still a
     * call. Checking only the name recorded every returned call as a definition — and since a name
     * cannot be both, the reference was then removed, erasing the very caller edge the index holds.
     */
    private static boolean declaresRatherThanCalls(String line, int nameStart, Set<String> controlKeywords) {
        int before = skipWhitespaceBack(line, nameStart);
        if (before == 0 || before >= nameStart) {
            return false;
        }
        char preceding = line.charAt(before - 1);
        if (!Character.isJavaIdentifierPart(preceding) && preceding != '>' && preceding != ']') {
            return false;
        }
        int wordStart = before;
        while (wordStart > 0 && Character.isJavaIdentifierPart(line.charAt(wordStart - 1))) {
            wordStart--;
        }
        return !controlKeywords.contains(line.substring(wordStart, before).toLowerCase(Locale.ROOT));
    }

    private static int skipWhitespaceBack(String line, int from) {
        int at = from;
        while (at > 0 && Character.isWhitespace(line.charAt(at - 1))) {
            at--;
        }
        return at;
    }
}
