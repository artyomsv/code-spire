package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared fixture of the harness review start tests: a plain unit test's fakes, each overriding every method the
 * path reaches, because an un-overridden one opens a real database from a unit test (CLAUDE.md, saga test fakes).
 * The dispatch fake is kept to prove that the start never dispatches.
 */
abstract class HarnessReviewStartFixture {

    static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    static final long PR = 7L;
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    static final UUID REPOSITORY = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    static final int CURRENT_RUN = 2;
    static final ContextAssembled EVENT =
            new ContextAssembled(REVIEW_ID, PR, HEAD, "TEST-context-ref", Set.of(), Set.of());
    static final PriorRun PRIOR_WITH_FINDING = new PriorRun("TEST-prior-commit", "TEST-summary-ref",
            List.of(new PriorFinding("src/TEST.java", 3, Severity.MAJOR, "TEST-message", "TEST-thread")));

    Optional<ReviewHarness> harness =
            Optional.of(new ReviewHarness("TEST-harness", "TEST-model", "high", "SUBSCRIPTION"));
    Optional<PriorRun> prior = Optional.empty();
    Boolean fromFork = false;
    String providerType = "github";
    int round = CURRENT_RUN;
    Optional<String> reviewerCredential = Optional.of("TEST-packed-scm");

    final List<ReviewRunRequest> dispatched = new ArrayList<>();
    final List<ActionCommand> emitted = new ArrayList<>();

    ActionCommand.PrepareHarnessReview onlyPrepare() {
        assertEquals(1, emitted.size(), "one prepare is sent: " + emitted);
        return assertInstanceOf(ActionCommand.PrepareHarnessReview.class, emitted.getFirst());
    }

    void assertNothingSent() {
        assertTrue(emitted.isEmpty(), "nothing is sent to the worker: " + emitted);
        assertTrue(dispatched.isEmpty(), "and no run is dispatched: " + dispatched);
    }

    HarnessReviewStart start() {
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

    RepositoryRegistry repositories() {
        return new RepositoryRegistry() {
            @Override
            public Optional<ReviewHarness> reviewHarness(UUID repositoryId) {
                return REPOSITORY.equals(repositoryId) ? harness : Optional.empty();
            }
        };
    }

    ReviewProjection projection() {
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

    FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                return Optional.of(new PushTarget(providerType, REPO.workspace(), REPO.slug(), PR, "TEST-source",
                        "TEST-main", HEAD, "OPEN", fromFork));
            }
        };
    }

    /** Recorded only to prove the start never dispatches; the launch does (step 6). */
    ReviewRunDispatch dispatch() {
        return new ReviewRunDispatch() {
            @Override
            public Outcome dispatch(ReviewRunRequest request) {
                dispatched.add(request);
                return new Dispatched("TEST-run-1");
            }
        };
    }

    /** The real ones read the event log; the ledger's read answers run 1 when the log cannot be read. */
    ReviewRuns runs() {
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
    WorkerCredentials credentials() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return reviewerCredential;
            }
        };
    }

    /** The real one awaits a Kafka ack. */
    CommandsEmitter commands() {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
    }
}
