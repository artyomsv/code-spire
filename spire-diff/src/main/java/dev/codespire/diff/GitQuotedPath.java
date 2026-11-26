package dev.codespire.diff;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Git's C-style path quoting, read back. Git wraps a path in double quotes and escapes it when it holds a control
 * character, a double quote, a backslash, or (with {@code core.quotePath}, the default) any non-ASCII byte, which it
 * writes as octal: {@code "src/caf\303\251.java"}. A space alone is never quoted. Headers and {@code rename} lines
 * carry such paths, and a parser that does not read them drops the file section with no error.
 */
final class GitQuotedPath {

    private static final char QUOTE = '"';
    private static final char ESCAPE = '\\';
    private static final int OCTAL_DIGITS = 3;
    private static final int OCTAL_RADIX = 8;

    private GitQuotedPath() {
    }

    /** One path read from a line: its decoded text and the index just past it. */
    record Token(String path, int end) {
    }

    /** The decoded path when {@code text} is one quoted path, the text unchanged when it is not quoted, else null. */
    static String unquoteIfQuoted(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != QUOTE) {
            return text;
        }
        Token token = quoted(text, 0);
        return token != null && token.end() == text.length() ? token.path() : null;
    }

    /**
     * The quoted path starting at {@code from} (which must hold the opening quote), or null when it is unterminated
     * or holds an escape Git never writes.
     */
    static Token quoted(String text, int from) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = from + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == QUOTE) {
                return new Token(bytes.toString(StandardCharsets.UTF_8), i + 1);
            }
            if (c != ESCAPE) {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i++;
                continue;
            }
            int consumed = escape(text, i + 1, bytes);
            if (consumed == 0) {
                return null;
            }
            i += 1 + consumed;
        }
        return null;
    }

    /** Writes the byte the escape at {@code at} stands for; returns how many characters it used, 0 when invalid. */
    private static int escape(String text, int at, ByteArrayOutputStream bytes) {
        if (at >= text.length()) {
            return 0;
        }
        char c = text.charAt(at);
        int simple = simpleEscape(c);
        if (simple >= 0) {
            bytes.write(simple);
            return 1;
        }
        if (at + OCTAL_DIGITS > text.length()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(text.substring(at, at + OCTAL_DIGITS), OCTAL_RADIX);
            bytes.write(value);
            return OCTAL_DIGITS;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int simpleEscape(char c) {
        return switch (c) {
            case 'a' -> 0x07;
            case 'b' -> '\b';
            case 't' -> '\t';
            case 'n' -> '\n';
            case 'v' -> 0x0B;
            case 'f' -> '\f';
            case 'r' -> '\r';
            case '"' -> '"';
            case '\\' -> '\\';
            default -> -1;
        };
    }

    /**
     * Splits {@code a/X b/Y} after {@code diff --git } when either path is quoted, into the two paths without their
     * {@code a/} and {@code b/} prefixes; null when the text is not such a pair.
     */
    static String[] splitQuotedPair(String rest) {
        String first;
        String second;
        if (!rest.isEmpty() && rest.charAt(0) == QUOTE) {
            Token token = quoted(rest, 0);
            if (token == null || token.end() >= rest.length() || rest.charAt(token.end()) != ' ') {
                return null;
            }
            first = token.path();
            second = unquoteIfQuoted(rest.substring(token.end() + 1));
        } else {
            int split = rest.lastIndexOf(" " + QUOTE);
            if (split < 0) {
                return null;
            }
            first = rest.substring(0, split);
            second = unquoteIfQuoted(rest.substring(split + 1));
        }
        if (second == null || !first.startsWith("a/") || !second.startsWith("b/")) {
            return null;
        }
        return new String[]{first.substring(2), second.substring(2)};
    }
}
