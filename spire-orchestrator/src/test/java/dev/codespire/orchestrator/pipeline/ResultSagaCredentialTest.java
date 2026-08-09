package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.context.WorkerContextCredentials;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Turning a failed review into a standing "this credential is dead" fact — the only path by
 * which real work teaches the panel that a token stopped working.
 */
class ResultSagaCredentialTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 1L);
    private static final ScmProvider PROVIDER = new ScmProvider(UUID.randomUUID(), "test provider", "stub",
            null, "TEST-WS", "token", null, "secret", "bot-1", true, List.of(), null, null);

    /** Recorded against the review's OWN provider, resolved by its stored provider type. */
    @Test
    void aCredentialRejectedFailureMarksTheReviewsProvider() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry, Optional.of(PROVIDER));

        saga.on(new ReviewFailed(REVIEW_ID, "abc", "fetch-diff", "boom", false, 1, true));

        assertEquals(1, registry.calls);
        assertFalse(registry.lastOk);
        // Never the provider's own response body: a 401 body may echo the token back.
        assertEquals("Authentication rejected (HTTP 401)", registry.lastDetail);
    }

    /** An ordinary failure must leave the credential's standing untouched. */
    @Test
    void anOrdinaryFailureRecordsNothing() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry, Optional.of(PROVIDER));

        saga.on(new ReviewFailed(REVIEW_ID, "abc", "generate", "boom", true, 1, false));

        assertEquals(0, registry.calls);
    }

    /** A review whose provider cannot be resolved must not blow up the result path. */
    @Test
    void anUnresolvableProviderIsSkippedQuietly() {
        RecordingProviderRegistry registry = new RecordingProviderRegistry();
        ResultSaga saga = sagaWith(registry, Optional.empty());

        saga.on(new ReviewFailed(REVIEW_ID, "abc", "fetch-diff", "boom", false, 1, true));

        assertEquals(0, registry.calls);
        assertNull(registry.lastDetail);
    }

    /**
     * The other consumer of {@link ReviewProviderResolver} on this saga (Task 1): {@code GatherContext}
     * carries the review's resolved SCM platform so a context provider can tell whether a repo-relative
     * reference belongs to its host. A review whose provider cannot be resolved must fail closed to a
     * null {@code scmType} rather than defaulting to a guess — the resolved-provider branch (a real
     * {@code ScmType} reaching the emitted command) is covered end-to-end over a real broker in
     * {@code OrchestratorChoreographyTest}, where a provider is actually registered.
     */
    @Test
    void unresolvableProviderLeavesTheGatherContextsScmTypeNull() {
        List<ActionCommand> emitted = new ArrayList<>();
        ResultSaga saga = sagaForDiffFetched(emitted, Optional.empty());

        saga.on(new DiffFetched(REVIEW_ID, 1, "abc123", 1, List.of("java"), 10, false, Set.of(), null));

        assertEquals(1, emitted.size(), "one GatherContext command emitted");
        var gatherContext = assertInstanceOf(ActionCommand.GatherContext.class, emitted.get(0));
        assertNull(gatherContext.scmType(),
                "an unresolvable provider must fail closed to null, never default to a platform");
    }

    /** Minimal {@link ResultSaga} wired for the DiffFetched -> GatherContext path only. */
    private static ResultSaga sagaForDiffFetched(List<ActionCommand> emitted, Optional<ScmProvider> resolved) {
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, 1L, ReviewState.Status.REVIEWING,
                        "abc123", Set.of(), null, Map.of());
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.workerContextCredentials = new WorkerContextCredentials() {
            @Override
            public Optional<String> packAll(String workspace) {
                return Optional.empty();
            }
        };
        saga.providers = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return resolved;
            }
        };
        // Task 5's diff-size gate reads this on every DiffFetched; unset here since this fixture's own
        // assertions are about scmType, not the cap — see DiffSizeGateTest for the gate itself.
        saga.capPolicy = new dev.codespire.orchestrator.caps.CapPolicy() {
            @Override
            public java.util.OptionalInt maxChangedFiles() {
                return java.util.OptionalInt.empty();
            }

            @Override
            public java.util.OptionalLong maxDiffBytes() {
                return java.util.OptionalLong.empty();
            }
        };
        return saga;
    }

    /**
     * Wires a {@link ResultSaga} the same way {@code ResultSagaRetryTest} does — direct field
     * assignment with hand-built fakes, no CDI container. {@code resolved} stands in for what
     * {@code ReviewProviderResolver} would find for this review's stored provider type.
     */
    private static ResultSaga sagaWith(RecordingProviderRegistry registry, Optional<ScmProvider> resolved) {
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public int currentAttempt(String reviewId) {
                return 1;
            }

            @Override
            public void scheduleRetry(String reviewId, int attempt, String note, Instant dueAt) {
            }

            @Override
            public void clearScheduledRetry(String reviewId) {
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
            }

            @Override
            public void setNote(String reviewId, String note) {
            }

            @Override
            public void setError(String reviewId, String error) {
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of();
            }
        };
        saga.reviewPolicy = new ReviewPolicy() {
            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public Duration retryDelay(int attempt) {
                return Duration.ofSeconds(1);
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("packed-cred");
            }
        };
        saga.providers = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return resolved;
            }
        };
        saga.providerRegistry = registry;
        return saga;
    }

    private static class RecordingProviderRegistry extends ProviderRegistry {
        int calls;
        boolean lastOk = true;
        String lastDetail;

        @Override
        public void recordCheck(UUID id, boolean ok, String detail) {
            calls++;
            lastOk = ok;
            lastDetail = detail;
        }
    }
}
