package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand.AnswerFollowUp;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReply;
import dev.codespire.contract.event.IntegrationEvent.FollowUpDeclined;
import dev.codespire.contract.event.FollowUpDeclineReason;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Whether the worker answers a reply it was asked to answer, the same for the API path ({@code FollowUpWorker}) and
 * the harness path ({@code HarnessReplyWorker}). The thread is re-fetched first (ADR-011): its participants decide
 * whether to engage, before any paid work. Every quiet exit sends a {@code FollowUpDeclined} (production PR #456),
 * because the orchestrator set "answering" when it sent the ask and nothing else would clear it.
 *
 * @param results           where a decline is sent
 * @param alreadyAnswered   claims the triggering comment, true when it was answered already; asked last, so a
 *                          declined reply claims nothing
 */
record ReplyGate(ResultsEmitter results, BooleanSupplier alreadyAnswered) {

    private static final Logger LOG = Logger.getLogger(ReplyGate.class);

    /** The reply being asked about: where its thread is, who triggered it, and whether it mentioned the bot. */
    record Ask(String reviewId, RepoRef repo, long prId, ThreadRef thread, String triggeringCommentId,
               boolean mentioned) {

        static Ask of(AnswerFollowUp command) {
            return new Ask(command.reviewId(), command.repo(), command.prId(), command.threadRef(),
                    command.triggeringCommentId(), command.mentioned());
        }

        static Ask of(PrepareHarnessReply command) {
            return new Ask(command.reviewId(), command.repo(), command.prId(), command.threadRef(),
                    command.triggeringCommentId(), command.mentioned());
        }
    }

    /** The thread to answer, or empty once the reply's {@code FollowUpDeclined} is sent. */
    Optional<ThreadTranscript> engage(CommentSink comments, Ask ask) {
        if (!(comments instanceof ThreadSource threadSource)) {
            return declined(ask, FollowUpDeclineReason.NO_THREAD_SOURCE);
        }
        // Scope "smart 1:1": auto-answer while it is the bot and one human; once a second human joins, stay quiet
        // unless the triggering comment mentioned the bot.
        ThreadTranscript transcript = threadSource.fetchThread(ask.repo(), ask.prId(), ask.thread());
        if (!FollowUpWorker.shouldAnswer(transcript, ask.mentioned())) {
            return declined(ask, FollowUpDeclineReason.MULTI_PARTY_NO_MENTION);
        }
        if (alreadyAnswered.getAsBoolean()) {
            return declined(ask, FollowUpDeclineReason.ALREADY_ANSWERED);
        }
        return Optional.of(transcript);
    }

    private Optional<ThreadTranscript> declined(Ask ask, FollowUpDeclineReason reason) {
        LOG.debugf("Not answering comment %s on %s: %s", ask.triggeringCommentId(), ask.reviewId(), reason);
        results.emit(new FollowUpDeclined(ask.reviewId(), ask.thread(), ask.triggeringCommentId(), reason));
        return Optional.empty();
    }
}
