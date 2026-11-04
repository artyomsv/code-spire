package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.HarnessReplyPrepared;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 7: a prepared reply dispatches one {@code FOLLOWUP} run per turn, under the API reply's own call
 * slot, and every refusal is said under the thread and clears "answering". A plain unit test: every collaborator the
 * launch reaches is a fake overriding every method it calls (CLAUDE.md, saga test fakes).
 */
class HarnessReplyLaunchTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final long PR = 7L;
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEWER_HEAD = "fedcba9876543210fedcba9876543210fedcba98";
    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000c8");
    private static final ThreadRef THREAD = new ThreadRef("TEST-thread-1");
    private static final HarnessReviewInputs INPUTS = new HarnessReviewInputs(List.of(
            new HarnessReviewInputs.InputFile(HarnessReviewInputs.THREAD, "TEST-THREAD")));

    private Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    private ReviewRunDispatch.Outcome refusal;
    private RuntimeException targetFailure;
    private final List<ReviewRunRequest> dispatched = new ArrayList<>();
    /** The rows a dispatch wrote, by call slot, as {@code factory_run.review_call_slot} holds them. */
    private final Map<String, String> rowsBySlot = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> timelineTypes = new ArrayList<>();
    private final List<Boolean> answering = new ArrayList<>();

    private static HarnessReplyPrepared prepared(String head, String comment) {
        return new HarnessReplyPrepared(REVIEW_ID, PR, head, THREAD, comment, 2, INPUTS);
    }

    @Test
    void aPreparedReplyDispatchesAFollowupRun() {
        launch().launch(prepared(HEAD, "TEST-comment-5"));

        ReviewRunRequest request = dispatched.getFirst();
        assertEquals(new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.FOLLOWUP, PR, HEAD,
                CallRefs.followUpSlot(THREAD.value(), "TEST-comment-5"), 2), request.binding());
        assertEquals(INPUTS, request.inputs());
        assertEquals(REPOSITORY, request.repositoryId());
        assertTrue(request.prompt().contains("Read /inputs/thread.md first"), request.prompt());
        assertTrue(request.choice().subscription(), "paid as the repository's harness setting says");
        assertTrue(timelineTypes.contains("harness-reply"), timelineTypes.toString());
        assertTrue(events.isEmpty(), "nothing refused: " + events);
    }

    @Test
    void aRedeliveredPreparedReplySendsNoSecondRun() {
        HarnessReplyLaunch launch = launch();

        launch.launch(prepared(HEAD, "TEST-comment-5"));
        launch.launch(prepared(HEAD, "TEST-comment-5"));

        assertEquals(1, dispatched.size());
    }

    /** The run id carries the head; the turn's slot does not, so a push between two prepares still sends one run. */
    @Test
    void aSecondPrepareOfOneTurnOnANewHeadSendsNoSecondRun() {
        HarnessReplyLaunch launch = launch();

        launch.launch(prepared(HEAD, "TEST-comment-5"));
        launch.launch(prepared(NEWER_HEAD, "TEST-comment-5"));

        assertEquals(1, dispatched.size());
        assertEquals(HEAD, dispatched.getFirst().binding().commit());
    }

    @Test
    void twoTurnsOfOneThreadAreTwoRuns() {
        HarnessReplyLaunch launch = launch();

        launch.launch(prepared(HEAD, "TEST-comment-5"));
        launch.launch(prepared(HEAD, "TEST-comment-6"));

        assertEquals(2, dispatched.size());
        assertNotEquals(runId(dispatched.get(0)), runId(dispatched.get(1)), "each turn is its own run and charge");
    }

    @Test
    void aRefusedDispatchRefusesTheReplyOutLoud() {
        refusal = new ReviewRunDispatch.Refused("subscription_unavailable");

        launch().launch(prepared(HEAD, "TEST-comment-5"));

        assertEquals(List.of("FollowUpRefused@TEST-thread-1: harness reply refused: TEST-harness: "
                + "subscription_unavailable"), events);
        assertEquals(List.of(false), answering, "the responding pill clears");
    }

    @Test
    void aCancelledAdmissionDropsTheReply() {
        refusal = new ReviewRunDispatch.Refused(ReviewRunDispatch.CANCELLED_REASON);

        launch().launch(prepared(HEAD, "TEST-comment-5"));

        assertEquals(List.of("FollowUpRefused@TEST-thread-1: pull request closed"), events);
        assertEquals(List.of(false), answering);
        assertTrue(timelineTypes.contains("dropped:HarnessReplyPrepared"), timelineTypes.toString());
    }

    @Test
    void aRemovedHarnessRefusesTheReply() {
        harness = Optional.empty();

        launch().launch(prepared(HEAD, "TEST-comment-5"));

        assertTrue(dispatched.isEmpty());
        assertEquals(List.of("FollowUpRefused@TEST-thread-1: harness reply refused: no harness: "
                + "review_harness_removed"), events);
        assertEquals(List.of(false), answering);
    }

    @Test
    void aRequestThatCannotBeComposedRefusesTheReply() {
        targetFailure = new IllegalStateException("TEST-target-read-failed");

        launch().launch(prepared(HEAD, "TEST-comment-5"));

        assertTrue(dispatched.isEmpty());
        assertEquals(List.of("FollowUpRefused@TEST-thread-1: harness reply refused: TEST-harness: "
                + "harness_review_start_failed"), events);
    }

    /** Once the command may be published a paid run may be executing: a dispatch that throws is not refused. */
    @Test
    void aDispatchThatThrowsIsNotRefused() {
        refusal = null;
        HarnessReplyLaunch launch = launch();
        launch.dispatch = new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                throw new IllegalStateException("TEST-broker-down");
            }
        };

        assertThrows(IllegalStateException.class, () -> launch.launch(prepared(HEAD, "TEST-comment-5")));
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    void aReplyWithoutACustomPromptGetsTheDefaultFollowupText() {
        launch().launch(prepared(HEAD, "TEST-comment-5"));

        String prompt = dispatched.getFirst().prompt();
        assertTrue(prompt.startsWith(PromptCatalog.defaultTemplate(PromptKind.FOLLOWUP).system()), prompt);
    }

    private static String runId(ReviewRunRequest request) {
        return ReviewRunIds.of(REPO, ScmType.GITHUB, request.binding());
    }

    private HarnessReplyLaunch launch() {
        HarnessReplyLaunch launch = new HarnessReplyLaunch();
        launch.repositories = new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return REPOSITORY.equals(repositoryId) ? harness : Optional.empty();
            }
        };
        launch.projection = projection();
        launch.targets = targets();
        launch.dispatch = dispatch();
        launch.runRows = runRows();
        launch.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                return null;
            }
        };
        launch.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineTypes.add(type);
            }
        };
        return launch;
    }

    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public Optional<UUID> repositoryIdOf(String reviewId) {
                return Optional.of(REPOSITORY);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                events.add(type + "@" + threadRef + ": " + detail);
            }

            @Override
            public void setAnswering(String reviewId, boolean value) {
                answering.add(value);
            }
        };
    }

    private FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                if (targetFailure != null) {
                    throw targetFailure;
                }
                return Optional.of(new PushTarget("github", REPO.workspace(), REPO.slug(), PR, "TEST-source",
                        "TEST-main", HEAD, "OPEN", false));
            }
        };
    }

    /** Answers as the real dispatch does: a dispatched call's row is recorded under its slot. */
    private ReviewRunDispatch dispatch() {
        return new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                if (refusal != null) {
                    return refusal;
                }
                dispatched.add(request);
                String runId = runId(request);
                rowsBySlot.putIfAbsent(request.binding().callSlot(), runId);
                return new Dispatched(runId);
            }
        };
    }

    /** The real one reads factory_run.review_call_slot. */
    private FactoryRunProjection runRows() {
        return new FactoryRunProjection() {
            @Override
            public Optional<String> reviewRunWithSlot(String reviewId, String callSlot) {
                assertFalse(callSlot.isBlank());
                return Optional.ofNullable(rowsBySlot.get(callSlot));
            }
        };
    }
}
