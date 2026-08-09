package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The result half of the archived notice: it is recorded in the review's own history, and it consumes
 * no conversation turn — {@code FollowUpPosted} would have, which is why the notice has an event of
 * its own.
 *
 * <p>The null thread is the case that matters. {@code ArchivedNotified.threadRef} is null whenever the
 * notice went to the top-level PR comment — the {@code /review} and PR-update paths, i.e. the common
 * one — and {@code ReviewThreadView.rootOf} binds it into a statement, so a null would throw an NPE
 * inside a {@code try} whose {@code catch (SQLException)} cannot see it.
 */
@QuarkusTest
class ArchivedNoticeResultTest {

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Test
    void aTopLevelNoticeIsRecordedWithoutDereferencingItsNullThread() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);

        saga().on(new IntegrationEvent.ArchivedNotified(reviewId, null, "TEST-COMMENT"));

        ReviewDetail.EventView entry = noticeIn(pr).orElseThrow(
                () -> new AssertionError("the notice is missing from the review's history"));
        assertNull(entry.threadRef(), "a top-level notice belongs to no thread");
    }

    /** A threaded notice is filed under the conversation root, as every other turn of that thread is. */
    @Test
    void aThreadedNoticeIsFiledUnderItsConversationRoot() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        threads.markAnswerThread(reviewId, new ThreadRef("TEST-ANSWER"), new ThreadRef("TEST-ROOT"));

        saga().on(new IntegrationEvent.ArchivedNotified(reviewId, new ThreadRef("TEST-ANSWER"),
                "TEST-COMMENT"));

        assertEquals("TEST-ROOT", noticeIn(pr).orElseThrow().threadRef());
    }

    @Test
    void theNoticeConsumesNoConversationTurn() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef thread = new ThreadRef("TEST-CAPPED-THREAD");
        threads.bumpTurn(reviewId, thread, "TEST-COMMENT-0");
        assertEquals(1, threads.turnCount(reviewId, thread), "the counter is live before the notice");

        saga().on(new IntegrationEvent.ArchivedNotified(reviewId, thread, "TEST-COMMENT"));

        assertEquals(1, threads.turnCount(reviewId, thread),
                "a notice about being retired must not consume one of the conversation's turns");
    }

    private long seedReview() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        return pr;
    }

    private Optional<ReviewDetail.EventView> noticeIn(long pr) {
        List<ReviewDetail.EventView> events = projection.loadDetail(WS, REPO, pr).orElseThrow().events();
        return events.stream().filter(e -> "ArchivedNotified".equals(e.type())).findFirst();
    }

    /** Real read model, silent dashboard — the handler's whole job is what it writes to the row. */
    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.projection = projection;
        saga.threads = threads;
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        return saga;
    }
}
