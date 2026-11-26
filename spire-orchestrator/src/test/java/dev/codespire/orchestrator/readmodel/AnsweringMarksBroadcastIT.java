package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How many times a reply's ending broadcasts the review, counted on the real {@link AnsweringMarks} writing Postgres
 * (qa/M-2, review of PR #457). Only {@code touch} is counted instead of sent; the row and flag writes are real, so a
 * write that removes the row and flips the flag is the case that used to broadcast twice.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class AnsweringMarksBroadcastIT {

    private static final RepoRef REPO = new RepoRef("ws", "answering-broadcast-it");
    private static final String A = "TEST-comment-A";
    private static final String B = "TEST-comment-B";

    @Inject
    DataSource dataSource;

    @Inject
    ReviewProjection projection;

    private final List<String> touches = new ArrayList<>();

    /** The posted answer's case: its row goes and the flag flips, and the review is broadcast once, not twice. */
    @Test
    void anEndingThatRemovesTheRowAndFlipsTheFlagBroadcastsOnce() {
        String reviewId = registered(1);
        AnsweringMarks marks = counted();
        marks.start(new ReplyKey(reviewId, A));
        touches.clear();

        marks.finishAndBroadcast(new ReplyKey(reviewId, A));

        assertEquals(List.of(reviewId), touches);
    }

    /** Another reply keeps the flag: the row goes, the flag stays, and the review is still broadcast once. */
    @Test
    void anEndingWhileAnotherReplyKeepsTheMarkBroadcastsOnce() {
        String reviewId = registered(2);
        AnsweringMarks marks = counted();
        marks.start(new ReplyKey(reviewId, A));
        marks.start(new ReplyKey(reviewId, B));
        touches.clear();

        marks.finishAndBroadcast(new ReplyKey(reviewId, A));

        assertEquals(List.of(reviewId), touches);
    }

    /** No row left (a close removed it): nothing is written, and the caller's new line is still broadcast once. */
    @Test
    void anEndingWithNoRowLeftBroadcastsOnce() {
        String reviewId = registered(3);

        counted().finishAndBroadcast(new ReplyKey(reviewId, A));

        assertEquals(List.of(reviewId), touches);
    }

    /** The plain finish, used when nothing was recorded, broadcasts only on a change. */
    @Test
    void aPlainFinishBroadcastsOnlyWhenItChangedSomething() {
        String reviewId = registered(4);
        AnsweringMarks marks = counted();

        marks.finish(new ReplyKey(reviewId, A));
        assertEquals(List.of(), touches, "nothing to remove, nothing to say");

        marks.start(new ReplyKey(reviewId, A));
        touches.clear();
        marks.finish(new ReplyKey(reviewId, A));
        assertEquals(List.of(reviewId), touches);
    }

    /** The real writes on the injected data source; the broadcast counted instead of sent. */
    private AnsweringMarks counted() {
        AnsweringMarks marks = new AnsweringMarks();
        marks.dataSource = dataSource;
        marks.projection = new ReviewProjection() {
            @Override
            public void touch(String reviewId) {
                touches.add(reviewId);
            }
        };
        return marks;
    }

    private String registered(long prId) {
        String reviewId = "review::ws/answering-broadcast-it#" + prId;
        projection.registerHeader(reviewId, REPO, prId, "t", "a", "aid", "src", "dst", "TEST-head-" + prId,
                "http://x", "github", "reviewing", 0);
        return reviewId;
    }
}
