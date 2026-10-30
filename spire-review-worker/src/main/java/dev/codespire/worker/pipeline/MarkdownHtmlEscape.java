package dev.codespire.worker.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Escapes raw HTML in model text for a forge comment, leaving Markdown code as written (docs/SECURITY.md). */
final class MarkdownHtmlEscape {

    /** A backtick run, then text, then a run of the same length. */
    private static final Pattern CODE_SPAN = Pattern.compile("(?<!`)(`+)(.+?)(?<!`)\\1(?!`)", Pattern.DOTALL);

    private MarkdownHtmlEscape() {
    }

    /** {@code text} with every {@code <} outside code escaped; null is empty. */
    static String outsideCode(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        Matcher span = CODE_SPAN.matcher(text);
        int at = 0;
        while (span.find()) {
            sb.append(text.substring(at, span.start()).replace("<", "&lt;")).append(span.group());
            at = span.end();
        }
        return sb.append(text.substring(at).replace("<", "&lt;")).toString();
    }
}
