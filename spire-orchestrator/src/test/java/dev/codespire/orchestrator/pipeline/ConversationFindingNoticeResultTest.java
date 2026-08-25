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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * L4: {@code rootOf} binds {@code threadRef} into a statement, so a null would throw an NPE
     * inside a {@code try} whose {@code catch (SQLException)} cannot see it — the same hazard
     * {@code ArchivedNoticeResultTest} already guards for. Both emitters guarantee non-null today; a
     * DLQ replay of a hand-built record does not.
     */
    @Test
    void aConfirmationWithNoThreadIsRecordedWithoutDereferencingItsNullThread() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);

        saga().on(new IntegrationEvent.FindingConfirmed(reviewId, null, "TEST-CONFIRM-COMMENT"));

        ReviewDetail.EventView entry = eventIn(pr, "FindingConfirmed").orElseThrow(
                () -> new AssertionError("the confirmation is missing from the review's history"));
        assertNull(entry.threadRef(), "a threadless confirmation belongs to no thread");
    }

    /** Same hazard and same guard as the confirmation above, for the refusal. */
    @Test
    void aRefusalWithNoThreadIsRecordedWithoutDereferencingItsNullThread() {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);

        saga().on(new IntegrationEvent.FindingRefused(reviewId, null, "TEST-REFUSE-COMMENT"));

        ReviewDetail.EventView entry = eventIn(pr, "FindingRefused").orElseThrow(
                () -> new AssertionError("the refusal is missing from the review's history"));
        assertNull(entry.threadRef(), "a threadless refusal belongs to no thread");
    }

    /**
     * M1: {@code appendEvent} alone writes only {@code review_event}, which {@code ReviewDetail}'s
     * live refresh does not watch — it refetches on a summary {@code updated_at} bump. Without a
     * {@link ReviewProjection#touch}, the confirmation's timeline line landed in the database but
     * sat unseen until some unrelated write happened to push a fresh summary. Asserted the same way
     * {@code ReviewProjectionPriorRunIT#touchBumpsUpdatedAtAndBroadcasts} asserts it for {@code touch}
     * itself — reading {@code updated_at} back through {@code loadDetail} rather than the private
     * broadcast mechanism, because {@code updated_at} is exactly the field a live client's refresh
     * effect keys on.
     */
    @Test
    void aConfirmationBumpsTheLiveSummarySoTheTimelineLineIsSeen() throws InterruptedException {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");
        Instant before = projection.loadDetail(WS, REPO, pr).orElseThrow().updatedAt();

        Thread.sleep(5);
        saga().on(new IntegrationEvent.FindingConfirmed(reviewId, root, "TEST-CONFIRM-COMMENT"));

        Instant after = projection.loadDetail(WS, REPO, pr).orElseThrow().updatedAt();
        assertTrue(after.isAfter(before),
                "a confirmation must bump updated_at so a live client's refresh effect actually fires");
    }

    /** Same hazard and same guard as the confirmation above: a refusal is exactly as much a
     *  dashboard-visible action, and appendEvent alone never bumps updated_at either. */
    @Test
    void aRefusalBumpsTheLiveSummarySoTheTimelineLineIsSeen() throws InterruptedException {
        long pr = seedReview();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        ThreadRef root = new ThreadRef("TEST-ROOT");
        Instant before = projection.loadDetail(WS, REPO, pr).orElseThrow().updatedAt();

        Thread.sleep(5);
        saga().on(new IntegrationEvent.FindingRefused(reviewId, root, "TEST-REFUSE-COMMENT"));

        Instant after = projection.loadDetail(WS, REPO, pr).orElseThrow().updatedAt();
        assertTrue(after.isAfter(before),
                "a refusal must bump updated_at so a live client's refresh effect actually fires");
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
