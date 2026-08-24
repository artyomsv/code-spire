package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.CommentCommands;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.port.EventStore;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * A command with nowhere to anchor is told how to use it. This project has twice shipped a
     * silence that read as a lost webhook; a command that does nothing and says nothing is the same
     * failure.
     */
    @Test
    void findingOnASummaryCommentSaysWhyInsteadOfFilingNothingSilently() {
        long pr = liveReview();
        String reviewId = reviewIdFor(pr);

        sagaAllowingEveryone().on(finding(pr, "major something", HUMAN, null, null, commentIdOf(pr)));

        assertTrue(projection.openFindingsFor(reviewId).isEmpty(), "nothing to anchor, nothing filed");
        assertTrue(emitted.isEmpty(), "and nothing dispatched");
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

    // ---- fixtures ----------------------------------------------------------

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
