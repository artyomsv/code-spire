package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.EventLine;
import dev.codespire.orchestrator.readmodel.ReplyKey;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Sends the command {@code ConversationSaga} planned for a human's reply, after {@code IntegrationSaga}'s gates: the
 * reply is filed under the conversation root and, for a real answer, its comment joins the replies being answered
 * (V122): the "responding" mark stays up until every outstanding reply has ended.
 */
@ApplicationScoped
public class ReplyDispatch {

    @Inject
    ReviewProjection projection;

    @Inject
    AnsweringMarks answering;

    @Inject
    CommandsEmitter commands;

    public void send(AuthorReplied reply, ActionCommand command) {
        String author = reply.author() == null ? "unknown" : reply.author().username();
        // The COMMAND's threadRef, not the event's: the saga normalized it to the conversation root, so every turn of
        // one conversation is stored under the same ref — the review detail nests them all under the finding instead
        // of spilling later turns into a bogus "General discussion" with an under-counted label. The reply's own comment
        // id rides along (V122), so the dashboard can end this reply with its own answer or decline and no other.
        projection.appendEvent(new EventLine(reply.reviewId(), "integration", "AuthorReplied",
                "@" + author + ": " + Previews.of(reply.text()), threadRefOf(command), reply.commentId()));
        // Only for a real answer: the cap notice is fixed text with no LLM call, and flagging it would leave a
        // "responding…" mark up for a reply that never comes. Also bumps the live dashboard (fix #5).
        if (command instanceof ActionCommand.AnswerFollowUp || command instanceof ActionCommand.PrepareHarnessReply) {
            answering.start(new ReplyKey(reply.reviewId(), reply.commentId()));
        }
        commands.emit(command);
    }

    /** The conversation root both reply commands carry, so the human's reply is filed under it either way. */
    private static String threadRefOf(ActionCommand command) {
        return switch (command) {
            case ActionCommand.AnswerFollowUp c -> c.threadRef().value();
            case ActionCommand.PrepareHarnessReply c -> c.threadRef().value();
            case ActionCommand.NotifyTurnCap c -> c.threadRef().value();
            default -> null;
        };
    }
}
