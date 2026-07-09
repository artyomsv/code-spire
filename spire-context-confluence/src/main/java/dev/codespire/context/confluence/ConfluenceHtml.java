package dev.codespire.context.confluence;

import java.util.regex.Pattern;

/**
 * Reduces a Confluence storage-format body (XHTML with {@code <ac:...>} macros) to
 * plain text for the review prompt. Not a full HTML parser — the body is UNTRUSTED
 * (SECURITY.md) and only needs to be legible context, so we strip tags, decode the
 * handful of entities that actually occur, and collapse whitespace. Block-level
 * tags become newlines so paragraphs and list items don't run together.
 */
final class ConfluenceHtml {

    private static final Pattern SCRIPT_OR_STYLE =
            Pattern.compile("(?is)<(script|style)\\b.*?</\\1>");
    private static final Pattern BLOCK_TAG =
            Pattern.compile("(?i)</(p|div|li|tr|h[1-6]|table|ul|ol|blockquote)>|<br\\s*/?>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private ConfluenceHtml() {
    }

    static String toText(String storage) {
        if (storage == null || storage.isBlank()) {
            return "";
        }
        String text = SCRIPT_OR_STYLE.matcher(storage).replaceAll(" ");
        text = BLOCK_TAG.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = SPACES.matcher(text).replaceAll(" ");
        // Trim trailing spaces on each line, then squeeze runs of blank lines.
        text = text.replaceAll(" *\\n *", "\n");
        text = MANY_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
