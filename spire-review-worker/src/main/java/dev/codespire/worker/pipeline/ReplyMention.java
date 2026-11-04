package dev.codespire.worker.pipeline;

/**
 * The finished text of a posted reply: the forge's own mention of the person it answers, so they are notified, then
 * the answer, escaped once, whole, outside Markdown code (docs/SECURITY.md, output sanitization; CLAUDE.md), as every
 * other posted comment is. The mention comes from the adapter ({@code CommentSink.mention}), never from the model: the
 * reply prompt forbids hand-written mentions, which fail to link or notify the wrong account. Shared by the API reply
 * and the harness reply.
 */
final class ReplyMention {

    private ReplyMention() {
    }

    /**
     * @param mention the forge's mention of the reply's author, or null when the forge has none for them
     */
    static String posted(String mention, String answer) {
        String text = mention == null || mention.isBlank() ? answer : mention + " " + answer;
        return MarkdownHtmlEscape.outsideCode(text);
    }
}
