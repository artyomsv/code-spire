package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a review's first round runs on its repository's harness, and that nothing but a same-repository first review
 * of a harness repository is ever dispatched (harness reviews, step 5).
 *
 * <p>A plain unit test: every collaborator the path reaches is a fake overriding every method it calls, because an
 * un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 */
class HarnessReviewStartTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final long PR = 7L;
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final int CURRENT_RUN = 2;
    private static final ContextAssembled EVENT =
            new ContextAssembled(REVIEW_ID, PR, HEAD, "TEST-context-ref", Set.of(), Set.of());

    private Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    private Optional<PriorRun> prior = Optional.empty();
    private Boolean fromFork = false;
    private ReviewRunDispatch.Outcome dispatchOutcome = new ReviewRunDispatch.Dispatched("TEST-run-1");
    private int round = CURRENT_RUN;

    private final List<ReviewRunRequest> dispatched = new ArrayList<>();

    @Test
    void aRepositoryWithoutAHarnessIsNotAHarnessRun() {
        harness = Optional.empty();

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertInstanceOf(HarnessReviewStart.NotHarness.class, decision);
        assertTrue(dispatched.isEmpty(), "an API repository never dispatches a run");
    }

    /** Re-reviews stay on the API until step 6: a posted prior round takes today's path. */
    @Test
    void aReReviewIsNotAHarnessRun() {
        prior = Optional.of(new PriorRun("TEST-prior-commit", "TEST-summary-ref", List.of()));

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertInstanceOf(HarnessReviewStart.NotHarness.class, decision);
        assertTrue(dispatched.isEmpty(), "a re-review never dispatches a run in step 5");
    }

    /** Forks wait for step 8: refused with a reason the attention feed shows, never sent to the API either. */
    @Test
    void aForkIsRefusedAndNeverDispatched() {
        fromFork = true;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_not_reviewed_on_harness"), decision);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void anUnknownForkStatusIsRefused() {
        fromFork = null;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_not_reviewed_on_harness"), decision);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void aFirstReviewDispatchesARunOfItsRound() {
        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Started("TEST-run-1", "TEST-harness"), decision);
        assertEquals(1, dispatched.size());
        ReviewRunRequest request = dispatched.getFirst();
        assertEquals(REPOSITORY, request.repositoryId());
        assertEquals(new ReviewRunBinding(REVIEW_ID, ReviewRunBinding.Kind.REVIEW, PR, HEAD,
                CallRefs.reviewSlot(HEAD, CURRENT_RUN), CURRENT_RUN), request.binding());
        assertEquals("TEST-harness", request.choice().harness());
        assertEquals("TEST-model", request.choice().model());
        assertEquals("high", request.choice().effort());
        assertTrue(request.choice().subscription(), "SUBSCRIPTION pays with a seat");
        assertTrue(request.prompt() != null && !request.prompt().isBlank(), "the run is sent its prompt");
    }

    @Test
    void aRefusedDispatchIsRefusedWithItsReason() {
        dispatchOutcome = new ReviewRunDispatch.Refused("subscription_unavailable");

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "subscription_unavailable"), decision);
    }

    /**
     * A round that cannot be read is refused, never guessed (review of PR #368, code-quality/M-1): the ledger's read
     * would answer run 1, and a run bound to round 1 during round 2 is refused at admission or dropped as stale.
     */
    @Test
    void anUnreadableRoundIsRefusedAndNeverDispatched() {
        round = ReviewRuns.UNKNOWN_RUN;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "review_round_unknown"), decision);
        assertTrue(dispatched.isEmpty(), "no run is bound to a guessed round: " + dispatched);
    }

    /** An uncertain launch has its queued row; a lost one surfaces as REVIEW_STUCK, so it is not refused here. */
    @Test
    void anUncertainLaunchCountsAsStarted() {
        dispatchOutcome = new ReviewRunDispatch.Uncertain("TEST-run-1");

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Started("TEST-run-1", "TEST-harness"), decision);
    }

    private HarnessReviewStart start() {
        HarnessReviewStart start = new HarnessReviewStart();
        start.repositories = repositories();
        start.projection = projection();
        start.targets = targets();
        start.dispatch = dispatch();
        start.runs = runs();
        start.promptTemplates = prompts();
        return start;
    }

    private RepositoryRegistry repositories() {
        return new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return REPOSITORY.equals(repositoryId) ? harness : Optional.empty();
            }
        };
    }

    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public Optional<UUID> repositoryIdOf(String reviewId) {
                return Optional.of(REPOSITORY);
            }

            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return prior;
            }
        };
    }

    private FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget("github", REPO.workspace(), REPO.slug(), PR, "TEST-source",
                        "TEST-main", HEAD, "OPEN", fromFork));
            }
        };
    }

    private ReviewRunDispatch dispatch() {
        return new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                dispatched.add(request);
                return dispatchOutcome;
            }
        };
    }

    /** The real ones read the event log; the ledger's read answers run 1 when the log cannot be read. */
    private ReviewRuns runs() {
        return new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return round == ReviewRuns.UNKNOWN_RUN ? ReviewRuns.FIRST_RUN : round;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return round;
            }
        };
    }

    private static WorkerPromptTemplates prompts() {
        return new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                return PromptCatalog.defaultTemplate(kind);
            }
        };
    }
}
