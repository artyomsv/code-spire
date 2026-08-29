package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.CommentCommands;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.port.EventStore;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewDetail;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO_REF;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.reviewIdFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code /finding} end to end through the saga: the aggregate append, the encrypted read-model write
 * and the in-thread confirmation, plus the two refusals — which are deliberately different from each
 * other.
 *
 * <p>{@code @QuarkusTest} with the REAL aggregate, read model and thread view. The idempotency this
 * suite asserts lives in the decider's own state, folded from the event store: with a faked
 * {@link ReviewLifecycleService} the redelivery test would assert against the fake's memory rather
 * than against the mechanism. Only the edges — the command bus, the timeline, the provider registry
 * and the credential broker — are hand-rolled fakes, in the style of {@link ArchivedEventGateTest}.
 */
@QuarkusTest
class ConversationFindingSagaTest {

    private static final Author HUMAN = Author.of("TEST-HUMAN-ID", "TEST-HUMAN", "Test Human");
    private static final Author STRANGER = Author.of("TEST-MALLORY-ID", "TEST-MALLORY", "Test Mallory");
    private static final String PATH = "TEST/Example.java";
    private static final int LINE = 44;
    private static final String LOC = PATH + ":" + LINE;

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    EventStore eventStore;

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> timelineDetails = new ArrayList<>();

    @Test
    void findingCommandFilesTheFindingAndConfirmsInThread() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);

        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        ReviewDetail.FindingView filed = findingAt(reviewId, LOC);
        assertEquals("warning", filed.sev(), "the leading severity word is what was filed");
        assertEquals("shadows the field", filed.msg());
        assertEquals("conversation", filed.origin(), "a human filed this, and a reader must not guess");
        assertEquals(rootRefOf(pr), filed.threadRef());

        ActionCommand.ConfirmFinding confirm = onlyConfirmation();
        assertEquals(Severity.MAJOR, confirm.severity());
        assertEquals(PATH, confirm.path());
        assertEquals(LINE, confirm.line());
        assertEquals(rootRefOf(pr), confirm.threadRef().value());
        assertEquals(commentIdOf(pr), confirm.triggeringCommentId(),
                "the worker claims on the triggering comment, so the command has to carry it");
        assertEquals("TEST-PACKED-CREDENTIAL", confirm.scmCredential());
        assertEquals(REPO_REF, confirm.repo());
        assertEquals(pr, confirm.prId());
    }

    /**
     * {@code raisedFindingComments().contains(e.commentId())} is an immutable-set {@code contains}
     * call, which throws on {@code null} rather than answering false. The convenience
     * {@code ManualCommandReceived(repo, prId, command, args, author)} constructor makes a null
     * {@code commentId} constructible (it nulls threadRef/location too, so that path never actually
     * reaches this check) — this test drives the full constructor directly, with a real anchor, to
     * exercise the guard itself rather than rely on the convenience constructor's happy accident.
     */
    @Test
    void aNullCommentIdDoesNotCrashTheRedeliveryCheck() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        ManualCommandReceived command = new ManualCommandReceived(REPO_REF, pr, CommentCommands.FINDING,
                "major shadows the field", HUMAN, new ThreadRef(rootRefOf(pr)),
                new ThreadLocation(PATH, LINE), null);

        sagaAllowingEveryone().on(command);

        assertEquals("shadows the field", findingAt(reviewId, LOC).msg());
        assertNull(onlyConfirmation().triggeringCommentId());
    }

    /**
     * A plain top-level PR comment carries {@code threadRef = null} on every provider — GitHub sends
     * exactly the same {@code null} for a reply to the bot's own summary comment, since its {@code
     * issue_comment} webhook has no thread concept at all. So this is the case that used to leave a
     * GitHub {@code /finding} misuse silent, typed either way: a command with nowhere to anchor was
     * told nothing, which this project has twice shipped and learned to stop shipping.
     *
     * <p>Routes exactly like a top-level follow-up reply ({@code ConversationSaga#resolveThread}) —
     * into the review's posted summary thread, when one has been posted.
     */
    @Test
    void findingOnATopLevelCommentIsRefusedInTheSummaryThread() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        String summaryRef = "TEST-SUMMARY-" + pr;
        projection.recordPosted(reviewId, "TESTSHA" + pr, summaryRef);

        sagaAllowingEveryone().on(finding(pr, "major something", HUMAN, null, null, commentIdOf(pr)));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty(), "nothing to anchor, nothing filed");
        assertTrue(timelineDetails.stream().anyMatch(d -> d.contains("needs to be on a specific line")),
                "the refusal must say how to use the command; timeline was " + timelineDetails);
        ActionCommand.RefuseFinding refusal = onlyRefusal();
        assertEquals(summaryRef, refusal.threadRef().value(),
                "posted where a top-level follow-up answer would have gone, not left silent");
    }

    /**
     * The genuine floor: a top-level comment on a review that has never posted a summary has nowhere
     * to reply into at all (same as {@link ConversationSaga#resolveThread}'s own empty case), so this
     * stays timeline-only rather than inventing somewhere to post.
     */
    @Test
    void findingOnATopLevelCommentWithNoPostedSummaryStaysTimelineOnly() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);

        sagaAllowingEveryone().on(finding(pr, "major something", HUMAN, null, null, commentIdOf(pr)));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty(), "nothing to anchor, nothing filed");
        assertTrue(emitted.isEmpty(), "nowhere posted yet -- nothing to reply into");
        assertTrue(timelineDetails.stream().anyMatch(d -> d.contains("needs to be on a specific line")),
                "the refusal must say how to use the command; timeline was " + timelineDetails);
    }

    /**
     * Not every provider reports a location on every comment surface, so the stored thread row is the
     * fallback — and it is read from the conversation ROOT. On an SCM that threads by immediate
     * parent, a command typed in a reply carries that reply's id: the anchor lives on the root, and
     * so does every other fact about the conversation.
     */
    @Test
    void aCommandWithNoLocationAnchorsOnItsConversationRoot() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        ThreadRef root = new ThreadRef(rootRefOf(pr));
        ThreadRef reply = new ThreadRef(rootRefOf(pr) + "-REPLY");
        threads.markFindingThread(reviewId, root, PATH, LINE);
        threads.markAnswerThread(reviewId, reply, root);

        sagaAllowingEveryone().on(finding(pr, "blocker drops the lock", HUMAN, reply, null,
                commentIdOf(pr)));

        ReviewDetail.FindingView filed = findingAt(reviewId, LOC);
        assertEquals("critical", filed.sev());
        assertEquals(root.value(), filed.threadRef(),
                "the finding belongs to the conversation, not to the reply that carried the command");
        assertEquals(root, onlyConfirmation().threadRef(),
                "and the confirmation is posted into that same conversation");
    }

    /**
     * The refusal case the design cares about most, and the one the summary-comment test above does
     * NOT cover: a thread exists, so a reply could be posted into it, and there is still no line
     * anywhere — neither on the event nor on the thread's stored row. Reached by a {@code /finding}
     * typed in the review's summary thread.
     *
     * <p>Unlike the no-thread-at-all case, silence here is the failure this project has shipped
     * twice before: there IS somewhere to post, so the human must be told, not just the timeline.
     */
    @Test
    void findingInAThreadWithNoAnchorAnywhereIsRefusedToo() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        ThreadRef summary = new ThreadRef(rootRefOf(pr));
        threads.markSummaryThread(reviewId, summary);
        assertNull(threads.locationOf(reviewId, summary),
                "a row that exists without a location must read back as no location, not as ':0'");

        sagaAllowingEveryone().on(finding(pr, "major something", HUMAN, summary, null,
                commentIdOf(pr)));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty());
        assertTrue(timelineDetails.stream().anyMatch(d -> d.contains("needs to be on a specific line")),
                "the refusal must say how to use the command; timeline was " + timelineDetails);
        ActionCommand.RefuseFinding refusal = onlyRefusal();
        assertEquals(summary, refusal.threadRef(), "the reply lands in the thread the command came from");
        assertEquals(REPO_REF, refusal.repo());
        assertEquals(pr, refusal.prId());
        assertEquals("TEST-PACKED-CREDENTIAL", refusal.scmCredential());
    }

    /**
     * Nothing else stops a {@code /finding} on a PR that was never registered: {@code archived}
     * answers false for a row that does not exist, and the provider resolves by workspace when the
     * review has no stored type, so the command clears both gates ahead of it.
     *
     * <p>Both halves matter. The read model drops the finding with a WARN, so a confirmation would
     * announce a finding that exists nowhere — and the aggregate would keep the comment id, making
     * a later registration plus redelivery an idempotent no-op. That finding could never be filed.
     */
    @Test
    void findingOnAnUnregisteredPrFilesNothingAndConfirmsNothing() {
        long pr = ReviewFixtures.newPr();   // deliberately NOT seeded — no review_status row
        String reviewId = reviewIdFor(pr);

        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        assertTrue(emitted.isEmpty(), "confirming a finding stored nowhere tells the human a lie");
        assertTrue(lifecycle.currentState(reviewId).raisedFindingComments().isEmpty(),
                "and burning the comment id would make the finding unfileable forever");
        assertTrue(timelineDetails.stream().anyMatch(d -> d.contains("no registered review")),
                "refused in /review's own idiom; timeline was " + timelineDetails);

        // The other half: register the PR, redeliver the same comment, and it files for real.
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        assertEquals("shadows the field", findingAt(reviewId, LOC).msg());
        assertEquals(1, confirmations().size());
    }

    /**
     * L3: the {@code Filed} path above is gated by {@code canFileConversationFinding}'s registered()
     * check, but the {@code Refused} path used to have none — {@code refuseConversationFinding}
     * posted directly into any thread it could resolve. {@code review_thread} carries no FK to
     * {@code review_status}, so a thread can exist for a PR that was never registered (marked here
     * directly, the way a summary thread would be from a review that later got deleted and never
     * re-registered), and the bot would reply on it regardless.
     */
    @Test
    void findingRefusalOnAnUnregisteredPrPostsNothing() {
        long pr = ReviewFixtures.newPr();   // deliberately NOT seeded — no review_status row
        String reviewId = reviewIdFor(pr);
        ThreadRef summary = new ThreadRef(rootRefOf(pr));
        threads.markSummaryThread(reviewId, summary);

        // No location anywhere -> a Refused outcome, with somewhere (the summary thread) to reply to.
        sagaAllowingEveryone().on(finding(pr, "major something", HUMAN, summary, null, commentIdOf(pr)));

        assertTrue(emitted.isEmpty(), "an unregistered PR must get no reply, even on the refusal path");
        assertTrue(timelineDetails.stream().anyMatch(d -> d.contains("no registered review")),
                "refused in /review's own idiom; timeline was " + timelineDetails);
    }

    /**
     * Proof that {@code /finding} inherits the gate {@code onManualCommand} puts ahead of the command
     * switch — the comment there says it sits high "so a future command cannot be added below it and
     * arrive ungated", and this is that future command.
     *
     * <p>The refusal is SILENT, unlike the one above. A reply would confirm to a prober that the
     * command is wired and cost an outbound comment per probe. The allowlisted control at the end is
     * what makes the two absence assertions mean something.
     */
    @Test
    void anAuthorOutsideTheAllowlistFilesNothingAndIsNotRepliedTo() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        IntegrationSaga saga = sagaFor(provider(List.of(HUMAN.username())));

        saga.on(finding(pr, "blocker anything", STRANGER, new ThreadRef(rootRefOf(pr)),
                new ThreadLocation(PATH, LINE), "TEST-COMMENT-STRANGER-" + pr));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty(),
                "an unlisted author files nothing");
        assertTrue(emitted.isEmpty(),
                "and is met with silence — a reply would confirm the command is wired");
        assertTrue(lifecycle.currentState(reviewId).raisedFindingComments().isEmpty(),
                "the aggregate never saw the command either");

        saga.on(finding(pr, "blocker anything", HUMAN, new ThreadRef(rootRefOf(pr)),
                new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        assertNotNull(findingAt(reviewId, LOC),
                "the control: the same command from an allowlisted author does file, so the "
                        + "assertions above are about the gate and not about a fixture that never worked");
        assertEquals(1, confirmations().size());
    }

    /**
     * Proof that {@code /finding} inherits ADR-024's archival gate: {@code archivedReviewIdOf} runs
     * ahead of the dispatch switch and {@code reviewIdOf} already handles this event, so no new code
     * is involved — which is exactly why it is asserted rather than assumed.
     */
    @Test
    void findingOnAnArchivedReviewIsRefusedByTheExistingGate() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));

        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty(),
                "an archived review is retired — nothing is written to it");
        assertTrue(lifecycle.currentState(reviewId).raisedFindingComments().isEmpty(),
                "and its aggregate is not advanced");
        assertTrue(confirmations().isEmpty(), "no confirmation for a finding that was never filed");
        assertEquals(1, emitted.size(), () -> "expected exactly the archived notice, got " + emitted);
        assertInstanceOf(ActionCommand.NotifyArchived.class, emitted.getFirst(),
                "the human is told the PR is retired rather than met with silence");
    }

    /**
     * {@code ManualCommandReceived} arrives at-least-once. The aggregate keys on the triggering
     * comment and answers a redelivery with an empty event list; the saga must READ that and stop,
     * or it re-posts the confirmation and re-writes the read model.
     *
     * <p>The visible harm is the second confirmation, and that is what is asserted: a duplicate
     * read-model write of the SAME message is invisible by design, because {@code mergeMessages}
     * deduplicates a group's constituents. So the emit — a real second comment in a human's thread —
     * is the assertion that discriminates.
     */
    @Test
    void aRedeliveredFindingCommandFilesOnlyOnce() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        IntegrationEvent event = finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr));
        IntegrationSaga saga = sagaAllowingEveryone();

        saga.on(event);
        saga.on(event);

        assertEquals(1, findingsAt(reviewId, LOC).size(), "one anchor stays one tracked concern");
        assertEquals(1, confirmations().size(),
                "a redelivery must not post a second confirmation into the human's thread");
        assertEquals(1, raisedEventsIn(reviewId),
                "and the aggregate appended the finding exactly once");
    }

    /**
     * The pre-check's own test — the guard the reorder above made necessary, and the one thing
     * {@code aRedeliveredFindingCommandFilesOnlyOnce} cannot see. That test passes with or without
     * the pre-check, because {@code handle} still answers empty (one event, one confirmation) and
     * {@code dedupeByAnchor} collapses the extra write to one row at the same anchor.
     *
     * <p>What the pre-check actually stops is a redelivery arriving AFTER a round judged the finding
     * and dropped it from the baseline. Projecting first would put a resolved finding back before
     * the aggregate ever got to answer. Reachable in production: a processing failure goes to
     * {@code cs.dlq}, and a DLQ replay is an operator action that can arrive hours later.
     */
    @Test
    void aRedeliveryAfterTheFindingWasResolvedDoesNotResurrectIt() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        ManualCommandReceived command = finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr));
        IntegrationSaga saga = sagaAllowingEveryone();
        saga.on(command);
        assertNotNull(findingAt(reviewId, LOC), "the finding has to exist before a round can resolve it");

        // A later round judges it RESOLVED: neither stillOpenPriorFindings nor
        // unmatchedConversationFindings carries a finding this round's verdicts matched, so
        // recordOpenFindings rewrites the baseline without it.
        projection.recordOpenFindings(reviewId,
                new ReviewResult(List.of(), "TEST-SUMMARY", ModelUsage.of("TEST-MODEL", 1, 1)),
                List.of(new FindingVerdict(rootRefOf(pr), PATH, LINE, FindingVerdict.Status.RESOLVED,
                        "TEST-NOTE")),
                List.of());
        assertTrue(findingsAt(reviewId, LOC).isEmpty(), "the round dropped it from the baseline");

        saga.on(command);

        assertTrue(findingsAt(reviewId, LOC).isEmpty(),
                "a redelivered command must not put a resolved finding back on the baseline");
        assertEquals(1, confirmations().size(), "and must not confirm it a second time");
    }

    /**
     * The ordering test. {@code addConversationFinding} throws on {@code SQLException} and nothing in
     * this saga catches it, so the message dead-letters — and the read model is this finding's ONLY
     * home, because {@code ConversationFindingRaised} deliberately carries no message (it may quote
     * source code, DATA-MODEL §5).
     *
     * <p>So the aggregate must not have consumed the triggering comment. Appending first did: the
     * replay then found the aggregate saying "already raised", returned early, and the human's
     * finding was gone permanently — from one transient database blip between two adjacent
     * statements. Projecting first makes the worst case a missing confirmation instead.
     *
     * <p>The second half is the half that matters: the replay files it for real.
     */
    @Test
    void aFailedProjectionLeavesTheFindingFileableInsteadOfDestroyingIt() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);
        IntegrationSaga saga = sagaAllowingEveryone();
        saga.projection = projectionFailingOnTheFindingWrite();

        assertThrows(IllegalStateException.class, () -> saga.on(finding(pr, "major shadows the field",
                HUMAN, new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr))),
                "the write fails and the message dead-letters — that part is expected");

        assertTrue(lifecycle.currentState(reviewId).raisedFindingComments().isEmpty(),
                "the comment id must not be consumed by a write that never landed: nothing could "
                        + "rebuild the finding, so a replay is its only chance");

        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        assertEquals("shadows the field", findingAt(reviewId, LOC).msg(),
                "the replay must file it for real");
        assertEquals(1, confirmations().size());
    }

    /**
     * The event reaches the review's own history through {@code DomainEventSink}, which describes it
     * for the timeline. Without a {@code describe} arm the switch's {@code default -> ""} leaves the
     * row a bare type name with no detail, which is invisible to every saga-level assertion because
     * the sink is a different consumer on a different topic — hence the round trip.
     *
     * <p>Anchor and severity only: the message may quote source code and the event does not carry it.
     */
    @Test
    void theRaisedEventReachesTheReviewHistoryWithItsAnchorAndSeverity() throws InterruptedException {
        long pr = liveReview();

        sagaAllowingEveryone().on(finding(pr, "major shadows the field", HUMAN,
                new ThreadRef(rootRefOf(pr)), new ThreadLocation(PATH, LINE), commentIdOf(pr)));

        ReviewDetail.EventView row = awaitHistoryRow(pr, "ConversationFindingRaised");
        assertEquals("MAJOR at " + LOC, row.det());
        assertFalse(row.det().contains("shadows the field"),
                "the finding's message may quote source code and must not reach the replayable log");
    }

    // ---- fixtures ----------------------------------------------------------

    /**
     * A live review whose finding write fails the way a transient database fault does. Only the three
     * methods this path touches are overridden; a real {@link ReviewProjection} cannot be used because
     * the failure has to be in the write and nowhere else.
     */
    private static ReviewProjection projectionFailingOnTheFindingWrite() {
        return new ReviewProjection() {
            @Override
            public boolean archived(String reviewId) {
                return false;
            }

            @Override
            public boolean registered(String reviewId) {
                return true;
            }

            @Override
            public void addConversationFinding(String reviewId, String threadRef, String path,
                                               int line, Severity severity, String message) {
                throw new IllegalStateException("review_status write failed");
            }
        };
    }

    /** Polls the review's history for one projected domain event — cs.events is a real round trip. */
    private ReviewDetail.EventView awaitHistoryRow(long pr, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<ReviewDetail.EventView> row = projection.loadDetail(WS, REPO, pr).orElseThrow()
                    .events().stream().filter(e -> type.equals(e.type())).findFirst();
            if (row.isPresent()) {
                return row.get();
            }
            Thread.sleep(100);
        }
        return fail(type + " was never projected — the sink never saw it, so this test proves nothing");
    }


    /** A completed review nobody has archived — the state a human is discussing a finding in. */
    private long liveReview() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        return pr;
    }

    private static String rootRefOf(long pr) {
        return "TEST-THREAD-" + pr;
    }

    private static String commentIdOf(long pr) {
        return "TEST-COMMENT-" + pr;
    }

    private static ManualCommandReceived finding(long pr, String args, Author author,
                                                 ThreadRef threadRef, ThreadLocation location,
                                                 String commentId) {
        return new ManualCommandReceived(REPO_REF, pr, CommentCommands.FINDING, args, author,
                threadRef, location, commentId);
    }

    private List<ReviewDetail.FindingView> findingsAt(String reviewId, String loc) {
        return projection.openFindingsFor(reviewId).stream().filter(f -> loc.equals(f.loc())).toList();
    }

    private ReviewDetail.FindingView findingAt(String reviewId, String loc) {
        List<ReviewDetail.FindingView> at = findingsAt(reviewId, loc);
        assertFalse(at.isEmpty(), () -> "no finding at " + loc + " — open findings were "
                + projection.openFindingsFor(reviewId));
        return at.getFirst();
    }

    private List<ActionCommand.ConfirmFinding> confirmations() {
        return emitted.stream().filter(ActionCommand.ConfirmFinding.class::isInstance)
                .map(ActionCommand.ConfirmFinding.class::cast).toList();
    }

    private ActionCommand.ConfirmFinding onlyConfirmation() {
        List<ActionCommand.ConfirmFinding> confirms = confirmations();
        assertEquals(1, confirms.size(), () -> "expected one confirmation, got " + emitted);
        return confirms.getFirst();
    }

    private List<ActionCommand.RefuseFinding> refusals() {
        return emitted.stream().filter(ActionCommand.RefuseFinding.class::isInstance)
                .map(ActionCommand.RefuseFinding.class::cast).toList();
    }

    private ActionCommand.RefuseFinding onlyRefusal() {
        List<ActionCommand.RefuseFinding> refused = refusals();
        assertEquals(1, refused.size(), () -> "expected one refusal, got " + emitted);
        return refused.getFirst();
    }

    private long raisedEventsIn(String reviewId) {
        return eventStore.load(reviewId).stream().map(EventEnvelope::payload)
                .filter(DomainEvent.ConversationFindingRaised.class::isInstance).count();
    }

    private static Optional<ScmProvider> provider(List<String> authors) {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-PROVIDER", "github",
                "https://example.invalid", WS, "bearer", null, "TEST-SECRET", "TEST-BOT-ACCOUNT",
                true, authors, null, null));
    }

    private IntegrationSaga sagaAllowingEveryone() {
        return sagaFor(provider(List.of()));
    }

    /** Real aggregate, read model and thread view; faked edges, so nothing is posted anywhere. */
    private IntegrationSaga sagaFor(Optional<ScmProvider> provider) {
        IntegrationSaga saga = new IntegrationSaga();
        // Both overridden on purpose. The real ones open a connection, so an un-overridden method
        // here turns this unit test into a live database call -- the trap already recorded for
        // setNote and recordCharges, and hit four times in this milestone alone.
        saga.findings = new dev.codespire.orchestrator.readmodel.FindingProjection() {
            @Override
            public void recordConversationFinding(String reviewId, int round, String commit,
                    String path, int line, String severity, String threadRef) {
            }
        };
        saga.runs = new dev.codespire.orchestrator.llm.ReviewRuns() {
            @Override
            public int roundOrUnknown(String reviewId) {
                return FIRST_RUN;
            }
        };
        saga.projection = projection;
        saga.threads = threads;
        saga.lifecycle = lifecycle;
        saga.policy = new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return false;
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineDetails.add(detail);
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                return provider;
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                return provider;
            }
        };
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return provider;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "TEST-PACKED-CREDENTIAL";
            }
        };
        saga.conversation = new ConversationSaga() {
            @Override
            public Optional<ActionCommand> planFollowUp(IntegrationEvent.AuthorReplied e) {
                throw new AssertionError("a /finding is not a follow-up question");
            }
        };
        saga.rerunService = new ReviewRerunService() {
            @Override
            public boolean rerun(String workspace, String slug, long pr) {
                throw new AssertionError("a /finding must never trigger a paid re-review");
            }
        };
        return saga;
    }
}
