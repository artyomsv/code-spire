package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a review runs on its repository's harness, and that a harness review is first sent to the worker to prepare
 * its inputs, a re-review as a reconcile (harness reviews, step 6). The dispatch itself is {@link HarnessReviewLaunch}'s
 * ({@code HarnessReviewLaunchTest}).
 *
 * <p>A plain unit test: every collaborator the path reaches is a fake overriding every method it calls, because an
 * un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes). The dispatch fake is kept
 * to prove that the start never dispatches.
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
    private static final PriorRun PRIOR_WITH_FINDING = new PriorRun("TEST-prior-commit", "TEST-summary-ref",
            List.of(new PriorFinding("src/TEST.java", 3, Severity.MAJOR, "TEST-message", "TEST-thread")));

    private Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    private Optional<PriorRun> prior = Optional.empty();
    private Boolean fromFork = false;
    private int round = CURRENT_RUN;
    private Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");

    private final List<ReviewRunRequest> dispatched = new ArrayList<>();
    private final List<ActionCommand> emitted = new ArrayList<>();

    @Test
    void aRepositoryWithoutAHarnessIsNotAHarnessRun() {
        harness = Optional.empty();

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertInstanceOf(HarnessReviewStart.NotHarness.class, decision);
        assertNothingSent();
    }

    @Test
    void aFirstReviewIsPreparedWithItsContext() {
        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(REVIEW_ID, prepare.reviewId());
        assertEquals(REPO, prepare.repo());
        assertEquals(PR, prepare.prId());
        assertEquals(HEAD, prepare.commit());
        assertEquals("TEST-context-ref", prepare.contextRef());
        assertEquals("TEST-packed-scm", prepare.scmCredential());
        assertNull(prepare.priorRun());
        assertEquals(CURRENT_RUN, prepare.round());
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepare.kind());
        assertTrue(dispatched.isEmpty(), "the run is dispatched once the worker has prepared it: " + dispatched);
    }

    /** Step 6 replaces step 5's "a re-review is not a harness run": the prior findings go to the worker to number. */
    @Test
    void aReReviewIsPreparedAsAReconcile() {
        prior = Optional.of(PRIOR_WITH_FINDING);

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(ReviewRunBinding.Kind.RECONCILE, prepare.kind());
        assertEquals(PRIOR_WITH_FINDING, prepare.priorRun());
        assertEquals(CURRENT_RUN, prepare.round());
        assertTrue(dispatched.isEmpty(), dispatched.toString());
    }

    /** The API path skips reconcile when nothing is open, so an empty reconcile is never charged. */
    @Test
    void aReReviewWithNoOpenFindingsIsPreparedAsAReview() {
        prior = Optional.of(new PriorRun("TEST-prior-commit", "TEST-summary-ref", List.of()));

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Preparing("TEST-harness"), decision);
        ActionCommand.PrepareHarnessReview prepare = onlyPrepare();
        assertEquals(ReviewRunBinding.Kind.REVIEW, prepare.kind());
        assertNull(prepare.priorRun());
    }

    /** Forks wait for step 8: refused with a reason the attention feed shows, never sent to the API either. */
    @Test
    void aForkIsRefusedAndNeverPrepared() {
        fromFork = true;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_not_reviewed_on_harness"), decision);
        assertNothingSent();
    }

    @Test
    void anUnknownForkStatusIsRefused() {
        fromFork = null;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "fork_pull_request_not_reviewed_on_harness"), decision);
        assertNothingSent();
    }

    /** A round that cannot be read is refused, never guessed (review of PR #368, code-quality/M-1). */
    @Test
    void anUnreadableRoundIsRefusedAndNeverPrepared() {
        round = ReviewRuns.UNKNOWN_RUN;

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "review_round_unknown"), decision);
        assertNothingSent();
    }

    /** Call 12: refused with attention, never left "reviewing" by a note-only skip. */
    @Test
    void aMissingReviewerCredentialIsRefused() {
        reviewerCredential = Optional.empty();

        HarnessReviewStart.Decision decision = start().start(EVENT);

        assertEquals(new HarnessReviewStart.Refused("TEST-harness", "reviewer_credential_unavailable"), decision);
        assertNothingSent();
    }

    private ActionCommand.PrepareHarnessReview onlyPrepare() {
        assertEquals(1, emitted.size(), "one prepare is sent: " + emitted);
        return assertInstanceOf(ActionCommand.PrepareHarnessReview.class, emitted.getFirst());
    }

    private void assertNothingSent() {
        assertTrue(emitted.isEmpty(), "nothing is sent to the worker: " + emitted);
        assertTrue(dispatched.isEmpty(), "and no run is dispatched: " + dispatched);
    }

    private HarnessReviewStart start() {
        HarnessReviewStart start = new HarnessReviewStart();
        start.repositories = repositories();
        start.projection = projection();
        start.targets = targets();
        start.dispatch = dispatch();
        start.runs = runs();
        start.credentials = credentials();
        start.commands = commands();
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

    /** Recorded only to prove the start never dispatches; the launch does (step 6). */
    private ReviewRunDispatch dispatch() {
        return new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                dispatched.add(request);
                return new Dispatched("TEST-run-1");
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

    /** The real one resolves the reviewer account from the database and packs it with Tink. */
    private WorkerCredentials credentials() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
    }

    /** The real one awaits a Kafka ack. */
    private CommandsEmitter commands() {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
    }
}
