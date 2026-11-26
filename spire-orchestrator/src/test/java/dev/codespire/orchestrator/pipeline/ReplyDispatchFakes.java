package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.ReplyKey;
import dev.codespire.orchestrator.readmodel.ReviewProjection;

import java.util.List;

/** {@link ReplyDispatch} over a saga test's own fakes, for the tests that drive a reply through {@code IntegrationSaga}. */
final class ReplyDispatchFakes {

    private ReplyDispatchFakes() {
    }

    /**
     * The real dispatch, writing through {@code projection} and sending through {@code commands}; each "answering"
     * mark it starts lands in {@code marks} as {@code reviewId:commentId}. The real marks open a database.
     */
    static ReplyDispatch recording(ReviewProjection projection, CommandsEmitter commands, List<String> marks) {
        ReplyDispatch replies = new ReplyDispatch();
        replies.projection = projection;
        replies.commands = commands;
        replies.answering = new AnsweringMarks() {
            @Override
            public void start(ReplyKey reply) {
                marks.add(reply.reviewId() + ":" + reply.commentId());
            }

            @Override
            public void finish(ReplyKey reply, Runnable recordEnding) {
                throw new AssertionError("dispatching a reply finishes no mark");
            }
        };
        return replies;
    }
}
