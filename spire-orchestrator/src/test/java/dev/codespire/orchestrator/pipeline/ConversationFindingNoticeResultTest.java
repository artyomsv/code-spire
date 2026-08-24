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

/**
 * The result half of a {@code /finding} confirmation or refusal, mirroring
 * {@link ArchivedNoticeResultTest}: the posted comment is recorded in the review's own history and
 * linked back to the conversation root, and neither consumes a conversation turn.
 *
 * <p>Before this, the worker's {@code confirmFinding} and {@code refuseFinding} emitted no result
 * event at all, so the link this test asserts never existed — a human replying to either posted
 * comment, on an SCM that threads by immediate parent, resolved to a fresh root instead of being
 * recognized as the same conversation.
 */
@QuarkusTest
class ConversationFindingNoticeResultTest {

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Test
    void aConfirmationIsFiledUnderItsConversationRootAndLinksItsCommentBack() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");

        saga().on(new IntegrationEvent.FindingConfirmed(reviewId, root, "TEST-CONFIRM-COMMENT"));

        assertEquals(root.value(), eventIn(pr, "FindingConfirmed").orElseThrow().threadRef());
        assertEquals(root, threads.rootOf(reviewId, new ThreadRef("TEST-CONFIRM-COMMENT")),
                "a reply to the confirmation must resolve back to the conversation it confirmed");
    }

    @Test
    void aRefusalIsFiledUnderItsConversationRootAndLinksItsCommentBack() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");

        saga().on(new IntegrationEvent.FindingRefused(reviewId, root, "TEST-REFUSE-COMMENT"));

        assertEquals(root.value(), eventIn(pr, "FindingRefused").orElseThrow().threadRef());
        assertEquals(root, threads.rootOf(reviewId, new ThreadRef("TEST-REFUSE-COMMENT")),
                "a reply to the refusal must resolve back to the conversation it refused");
    }

    @Test
    void aConfirmationConsumesNoConversationTurn() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");
        threads.bumpTurn(reviewId, root, "TEST-COMMENT-0");
        assertEquals(1, threads.turnCount(reviewId, root), "the counter is live before the confirmation");

        saga().on(new IntegrationEvent.FindingConfirmed(reviewId, root, "TEST-CONFIRM-COMMENT"));

        assertEquals(1, threads.turnCount(reviewId, root),
                "filing a finding is not the bot taking a turn in the conversation");
    }

    @Test
    void aRefusalConsumesNoConversationTurn() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");
        threads.bumpTurn(reviewId, root, "TEST-COMMENT-0");
        assertEquals(1, threads.turnCount(reviewId, root), "the counter is live before the refusal");

        saga().on(new IntegrationEvent.FindingRefused(reviewId, root, "TEST-REFUSE-COMMENT"));

        assertEquals(1, threads.turnCount(reviewId, root),
                "a refusal is not the bot taking a turn in the conversation");
    }

    private long seedReview() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        return pr;
    }

    private Optional<ReviewDetail.EventView> eventIn(long pr, String type) {
        List<ReviewDetail.EventView> events = projection.loadDetail(WS, REPO, pr).orElseThrow().events();
        return events.stream().filter(e -> type.equals(e.type())).findFirst();
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
