package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.context.WorkerContextCredentials;
import dev.codespire.orchestrator.factory.FixTargets;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness reviews, step 8 (operator ruling 2026-11-13): a fork author writes the title, branch and description the
 * links are read from, so an untrusted pull request gathers no linked issues or documents, on the API path and the
 * harness path alike. The PR text, the target branch's rules and the code references are kept.
 *
 * <p>Wired as {@code DiffSizeGateTest} is: hand-built fakes, no container. Every fake overrides each method the
 * DiffFetched path reaches (CLAUDE.md, saga test fakes).
 */
class ResultSagaUntrustedLinksTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    private static final String COMMIT = "abc123";
    private static final Set<String> LINKS = Set.of("TEST-1", "https://example.invalid/TEST-page");
    private static final CodeReferences CODE = new CodeReferences(Set.of("src/TEST.java"), Set.of("TESTSymbol"));

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> targetReads = new ArrayList<>();

    /** The row the fake target read answers with; null is a review whose row cannot be read. */
    private FixTargets.PushTarget fixedTarget;

    @Test
    void aForkGathersNoLinks() {
        sagaForTarget(target(true)).on(diffFetched(LINKS));

        assertTrue(gather().references().isEmpty(), gather().references().toString());
        assertTrue(events.contains("context links skipped: untrusted pull request"), events.toString());
    }

    @Test
    void anUnknownForkStatusGathersNoLinks() {
        sagaForTarget(target(null)).on(diffFetched(LINKS));

        assertTrue(gather().references().isEmpty(), gather().references().toString());
        assertTrue(events.contains("context links skipped: untrusted pull request"), events.toString());
    }

    @Test
    void aSameRepositoryPullRequestKeepsItsLinks() {
        sagaForTarget(target(false)).on(diffFetched(LINKS));

        assertEquals(LINKS, gather().references());
        assertTrue(events.stream().noneMatch(line -> line.contains("links skipped")), events.toString());
    }

    @Test
    void aForkKeepsItsRulesAndCodeReferences() {
        sagaForTarget(target(true)).on(diffFetched(LINKS));

        assertEquals("TEST-RULES", gather().repoRules());
        assertEquals(CODE, gather().codeReferences());
        assertEquals(7L, gather().prId());
    }

    /** A review whose row cannot be read has unknown provenance, which is untrusted. */
    @Test
    void aReviewWithNoRowGathersNoLinks() {
        sagaWithoutTarget().on(diffFetched(LINKS));

        assertTrue(gather().references().isEmpty(), gather().references().toString());
    }

    /** With no link there is nothing to cut, so nothing is read and nothing is said. */
    @Test
    void aPullRequestWithNoLinksReadsNoRowAndSaysNothing() {
        sagaForTarget(target(true)).on(diffFetched(Set.of()));

        assertTrue(gather().references().isEmpty());
        assertTrue(targetReads.isEmpty(), targetReads.toString());
        assertTrue(events.stream().noneMatch(line -> line.contains("links skipped")), events.toString());
    }

    private ActionCommand.GatherContext gather() {
        assertEquals(1, emitted.size(), "one GatherContext is emitted: " + emitted);
        return assertInstanceOf(ActionCommand.GatherContext.class, emitted.getFirst());
    }

    private static DiffFetched diffFetched(Set<String> references) {
        return new DiffFetched(REVIEW_ID, 7L, COMMIT, 1, List.of("java"), 10, false, references, "TEST-RULES", CODE);
    }

    private static FixTargets.PushTarget target(Boolean fromFork) {
        return new FixTargets.PushTarget("github", REPO.workspace(), REPO.slug(), 7L, "TEST-source", "TEST-main",
                COMMIT, "OPEN", fromFork);
    }

    private ResultSaga sagaForTarget(FixTargets.PushTarget target) {
        fixedTarget = target;
        return saga();
    }

    private ResultSaga sagaWithoutTarget() {
        fixedTarget = null;
        return saga();
    }

    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.timeline = timeline();
        saga.lifecycle = lifecycle();
        saga.projection = projection();
        saga.commands = commands();
        saga.workerContextCredentials = credentials();
        saga.providers = providers();
        saga.capPolicy = capPolicy();
        saga.targets = targets();
        return saga;
    }

    private TimelineBroadcaster timeline() {
        return new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
    }

    private ReviewLifecycleService lifecycle() {
        return new ReviewLifecycleService() {
            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, 7L, ReviewState.Status.REVIEWING,
                        COMMIT, Set.of(), null, Map.of(), Set.of());
            }
        };
    }

    private ReviewProjection projection() {
        return new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                events.add(detail);
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }
        };
    }

    private CommandsEmitter commands() {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
    }

    private WorkerContextCredentials credentials() {
        return new WorkerContextCredentials() {
            @Override
            public Optional<String> packAll(String workspace) {
                return Optional.empty();
            }
        };
    }

    private ReviewProviderResolver providers() {
        return new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.empty();
            }
        };
    }

    private CapPolicy capPolicy() {
        return new CapPolicy() {
            @Override
            public OptionalInt maxChangedFiles() {
                return OptionalInt.empty();
            }

            @Override
            public OptionalLong maxDiffBytes() {
                return OptionalLong.empty();
            }
        };
    }

    private FixTargets targets() {
        return new FixTargets() {
            @Override
            public Optional<PushTarget> forReview(String reviewId) {
                targetReads.add(reviewId);
                return Optional.ofNullable(fixedTarget);
            }
        };
    }
}
