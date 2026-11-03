package dev.codespire.worker.pipeline;

import java.util.Optional;

/**
 * Opens a posted reply with the forge's own mention of the person it answers, so they are notified. The mention comes
 * from the adapter ({@code CommentSink.mention}), never from the model: the reply prompt forbids hand-written mentions,
 * which fail to link or notify the wrong account. Shared by the API reply and the harness reply.
 */
final class ReplyMention {

    private ReplyMention() {
    }

    static String opened(Optional<String> mention, String answer) {
        return mention.filter(m -> !m.isBlank()).map(m -> m + " " + answer).orElse(answer);
    }
}
