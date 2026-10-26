package dev.codespire.orchestrator.work;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Untrusted Markdown quoted on a GitHub or GitLab ticket (M4 slice 3, A13): it reads as Markdown, its headings, lists
 * and code rendered, and it does nothing. Every line sits in one blockquote, so no line starts with the slash of a
 * GitLab quick action and nothing the text says can end the quote.
 *
 * <p>Two passes. The readable pass works on prose only, outside fences and code spans: an image is removed, so no remote
 * image loads (a tracking pixel least of all), a link becomes its text with the address in a code span, and a bare
 * address becomes a code span. The inert pass then runs over every line, code included, and puts an invisible
 * zero-width space after each character that would start something that acts: a tag, an HTML comment or an autolink
 * ({@code <}), a mention ({@code @}), an entity that could decode into one ({@code &}), an issue, pull request, merge
 * request, snippet, label, milestone or alert reference ({@code #1 !1 $1 GH-1 ~x %x ^x}), a link destination, reference
 * or definition ({@code ](}, {@code ][}, {@code ]:}) and an autolinked address ({@code ://}, {@code www.}). The inert
 * pass alone keeps the text from acting, so a fence or a code span that the readable pass misjudged can only read worse,
 * never link, load or ping.
 *
 * <p>A fence keeps its lines but loses its info string, so no diagram, map or model is rendered from it.
 */
final class MarkdownQuote {
    static final String IMAGE_REMOVED = "(image removed)";
    private static final String ZERO_WIDTH_SPACE = "\u200B";
    /** A fence line: at most three spaces, three or more backticks or tildes, then the info string. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    /** A code span: a backtick run and the next run of the same length on the line. */
    private static final Pattern CODE_SPAN = Pattern.compile("(?<!`)(`+).+?(?<!`)\\1(?!`)");
    private static final Pattern READABLE = Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"
            + "|\\[(?<text>[^\\]\\n]*)\\]\\(\\s*<?(?<href>[^)\\s>]*)>?[^)\\n]*\\)"
            + "|(?<url>\\b(?:[A-Za-z][A-Za-z0-9+.-]{0,31}://|(?i:www)\\.)[^\\s<>`()]*[^\\s<>`().,;:!?'\"\\]])");
    private static final Pattern INERT = Pattern.compile("[<@]|[#!$](?=\\d)|[~%^](?=[\\w\"])|&(?=[\\w#])"
            + "|\\](?=[(:\\[])|:(?=//)|(?i:\\bwww\\.)|(?i:\\bGH-)(?=\\d)");

    /** The fence that opened the code block the current line is in; null in prose. */
    private String openFence;

    private MarkdownQuote() {}

    /** The text, control characters already stripped, as a blockquote; trailing blank lines are dropped. */
    static String quote(String text) {
        MarkdownQuote quote = new MarkdownQuote();
        StringBuilder out = new StringBuilder();
        for (String line : text.stripTrailing().split("\n", -1)) {
            String rendered = inert(quote.render(line));
            out.append(out.isEmpty() ? "" : "\n").append(rendered.isEmpty() ? ">" : "> " + rendered);
        }
        return out.toString();
    }

    private String render(String line) {
        if (openFence != null) {
            if (closesFence(line)) openFence = null;
            return line;
        }
        Matcher fence = FENCE.matcher(line);
        if (!fence.matches()) return readable(line);
        openFence = fence.group(1);
        return line.substring(0, fence.end(1));
    }

    private boolean closesFence(String line) {
        String marks = line.strip();
        return marks.length() >= openFence.length() && marks.chars().allMatch(mark -> mark == openFence.charAt(0));
    }

    /** Prose made readable around the line's code spans, which are kept as written. */
    private static String readable(String line) {
        StringBuilder out = new StringBuilder();
        Matcher span = CODE_SPAN.matcher(line);
        int from = 0;
        while (span.find()) {
            out.append(prose(line.substring(from, span.start()))).append(span.group());
            from = span.end();
        }
        return out.append(prose(line.substring(from))).toString();
    }

    private static String prose(String text) {
        return READABLE.matcher(text).replaceAll(MarkdownQuote::replacement);
    }

    private static String replacement(MatchResult match) {
        if (match.group("url") != null) return Matcher.quoteReplacement(codeSpan(match.group("url")));
        if (match.group("text") == null) return IMAGE_REMOVED;
        String text = match.group("text"), address = codeSpan(match.group("href"));
        if (address.isEmpty()) return Matcher.quoteReplacement(text);
        return Matcher.quoteReplacement(text.isBlank() ? address : text + " (" + address + ")");
    }

    /** An address in a code span of one backtick; a backtick inside it is dropped so it cannot end the span. */
    private static String codeSpan(String address) {
        String plain = address.replace("`", "");
        return plain.isEmpty() ? "" : "`" + plain + "`";
    }

    private static String inert(String line) {
        return INERT.matcher(line).replaceAll("$0" + ZERO_WIDTH_SPACE);
    }
}
