package dev.codespire.worker.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Escapes raw HTML in a finished forge comment, leaving Markdown code as written (docs/SECURITY.md, output
 * sanitization).
 *
 * <p>A forge shows code literally, so an escaped {@code <} there reads as {@code &lt;} ({@code List&lt;Double>} on
 * GitHub, spire-test PR 82); everywhere else the escape is what keeps model text from becoming markup. "Code" must
 * therefore mean exactly what the forge's renderer treats as code, and hand-written rules kept disagreeing with it
 * (review of PR 364: mismatched runs, escaped openers, blank lines, headings). So a CommonMark parser with GitHub's
 * table extension decides: only the characters inside a code span, a fenced block or an indented block skip the
 * escape. It runs on the finished comment, because text around a message (the severity label) changes what the
 * message's own Markdown means. A code node the parser reports without a source position is treated as text, which
 * errs towards escaping; so is code inside a table cell, whose positions escaped pipes shift (commonmark 0.24.0 maps
 * them against the unescaped cell text, so a span's position lands before the span: review of PR 364, M-4).
 */
final class MarkdownHtmlEscape {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private MarkdownHtmlEscape() {
    }

    /** {@code text} with every {@code <} outside Markdown code escaped; null is empty. */
    static String outsideCode(String text) {
        if (text == null) {
            return "";
        }
        boolean[] code = codeCharacters(text);
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            out.append(c == '<' && !code[at] ? "&lt;" : String.valueOf(c));
        }
        return out.toString();
    }

    /** Which characters of {@code text} the parser places inside code. */
    private static boolean[] codeCharacters(String text) {
        boolean[] code = new boolean[text.length()];
        int[] lineStarts = lineStarts(text);
        CodeSpans spans = new CodeSpans();
        PARSER.parse(text).accept(spans);
        for (SourceSpan span : spans.found) {
            if (span.getLineIndex() < 0 || span.getLineIndex() >= lineStarts.length) {
                continue;
            }
            int start = Math.min(lineStarts[span.getLineIndex()] + span.getColumnIndex(), text.length());
            int end = Math.min(start + span.getLength(), text.length());
            Arrays.fill(code, start, end, true);
        }
        return code;
    }

    /** The offset each line starts at, splitting where CommonMark does: at {@code \n}, {@code \r\n} and {@code \r}. */
    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\r' && at + 1 < text.length() && text.charAt(at + 1) == '\n') {
                at++;
                starts.add(at + 1);
            } else if (c == '\n' || c == '\r') {
                starts.add(at + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Collects the source spans of every code node. */
    private static final class CodeSpans extends AbstractVisitor {

        private final List<SourceSpan> found = new ArrayList<>();

        @Override
        public void visit(Code code) {
            if (!insideTableCell(code)) {
                add(code);
            }
        }

        private static boolean insideTableCell(Node node) {
            for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof TableCell) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void visit(FencedCodeBlock block) {
            add(block);
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            add(block);
        }

        private void add(Node node) {
            found.addAll(node.getSourceSpans());
        }
    }
}
