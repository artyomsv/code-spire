package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.context.WorkerContextCredentials;
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
 * The diff-size gate sits between {@code DiffFetched} and the context fan-out it feeds — per-issue
 * Jira/Confluence/GitHub API calls, a bounded 20s wait, and an encrypted blob write. All of that runs
 * before a review is generated, so a diff too large to be worth reviewing should never trigger it.
 *
 * <p>Wired the same way as {@code ResultSagaCredentialTest#sagaForDiffFetched} — hand-built fakes,
 * no CDI container, {@link ResultSagaRetryTest}'s established posture for this saga.
 */
class DiffSizeGateTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 1L);
    private static final String COMMIT = "abc123";

    /**
     * The more important of the two tests: an unset cap must be a complete no-op, or every existing
     * deployment changes behaviour the moment this ships — the mistake V30 made by leaving legacy
     * models rateless. No setting is configured; the diff is deliberately huge, so this fails outright
     * if anyone later gives the limit a default.
     */
    @Test
    void anUnsetDiffLimitReviewsAnythingAtAll() {
        List<ActionCommand> emitted = new ArrayList<>();
        ResultSaga saga = sagaWith(emitted, new ArrayList<>(), unsetPolicy());

        saga.on(diffFetched(5_000, 900_000L));

        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.GatherContext.class, emitted.get(0),
                "an unset cap must be a no-op, or every existing deployment changes behaviour on upgrade");
    }

    @Test
    void anOversizedDiffIsRefusedBeforeContextIsGathered() {
        List<ActionCommand> emitted = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        ResultSaga saga = sagaWith(emitted, statuses, policyWithMaxChangedFiles(100));

        saga.on(diffFetched(5_000, 900_000L));

        assertTrue(emitted.isEmpty(), "GatherContext must not run for a diff we will not review");
        assertEquals(List.of("refused"), statuses);
    }

    /** A diff comfortably inside the limit must still be reviewed — the gate is one-sided. */
    @Test
    void aDiffWithinTheLimitIsUnaffected() {
        List<ActionCommand> emitted = new ArrayList<>();
        ResultSaga saga = sagaWith(emitted, new ArrayList<>(), policyWithMaxChangedFiles(100));

        saga.on(diffFetched(5, 900L));

        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.GatherContext.class, emitted.get(0));
    }

    /**
     * The second axis, which no test reached: both fake policies returned {@code OptionalLong.empty()}
     * for {@code maxDiffBytes} and every case drove {@code maxChangedFiles}, so a copy-paste in that
     * branch — comparing the file count against the byte limit, say — would have shipped green.
     *
     * <p>The file count is deliberately tiny, so only the byte axis can produce this refusal.
     */
    @Test
    void anOversizedDiffIsRefusedOnBytesEvenWithFewFiles() {
        List<ActionCommand> emitted = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        ResultSaga saga = sagaWith(emitted, statuses, policyWithMaxDiffBytes(1_000L));

        saga.on(diffFetched(2, 900_000L));

        assertTrue(emitted.isEmpty(), "GatherContext must not run for a diff we will not review");
        assertEquals(List.of("refused"), statuses);
    }

    /**
     * The boundary, on both axes. The diff gate refuses on {@code >} while the spend gate refuses on
     * {@code >=}, and both readings are defensible — a size <em>limit</em> is a ceiling a diff may
     * reach, a budget is exhausted once consumed. Nothing pinned either, so the difference between the
     * two gates was indistinguishable from the drift {@code SpendGate}'s javadoc names as the hazard
     * that bean exists to prevent. Both are now stated there and asserted here.
     */
    @Test
    void aDiffExactlyAtTheLimitIsReviewedAndOneOverIsNot() {
        assertEquals(List.of(), refusalStatusesFor(policyWithMaxChangedFiles(100), 100, 900L),
                "a 100-file limit says a 100-file diff is reviewable");
        assertEquals(List.of("refused"), refusalStatusesFor(policyWithMaxChangedFiles(100), 101, 900L));

        assertEquals(List.of(), refusalStatusesFor(policyWithMaxDiffBytes(1_000L), 2, 1_000L),
                "and the byte axis reads its limit the same way");
        assertEquals(List.of("refused"), refusalStatusesFor(policyWithMaxDiffBytes(1_000L), 2, 1_001L));
    }

    /** The statuses one DiffFetched writes under {@code policy} — empty when it was reviewed. */
    private static List<String> refusalStatusesFor(CapPolicy policy, int changedFiles, long sizeBytes) {
        List<String> statuses = new ArrayList<>();
        sagaWith(new ArrayList<>(), statuses, policy).on(diffFetched(changedFiles, sizeBytes));
        return statuses;
    }

    private static DiffFetched diffFetched(int changedFiles, long sizeBytes) {
        return new DiffFetched(REVIEW_ID, 1L, COMMIT, changedFiles, List.of("java"), sizeBytes, false,
                Set.of(), null);
    }

    private static CapPolicy unsetPolicy() {
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

    private static CapPolicy policyWithMaxChangedFiles(int limit) {
        return new CapPolicy() {
            @Override
            public OptionalInt maxChangedFiles() {
                return OptionalInt.of(limit);
            }

            @Override
            public OptionalLong maxDiffBytes() {
                return OptionalLong.empty();
            }
        };
    }

    /** The file axis is left unset, so a refusal under this policy can only have come from bytes. */
    private static CapPolicy policyWithMaxDiffBytes(long limit) {
        return new CapPolicy() {
            @Override
            public OptionalInt maxChangedFiles() {
                return OptionalInt.empty();
            }

            @Override
            public OptionalLong maxDiffBytes() {
                return OptionalLong.of(limit);
            }
        };
    }

    /** Wired the same way as {@code ResultSagaCredentialTest#sagaForDiffFetched}, with the cap policy
     *  and the status capture the refusal path needs. */
    private static ResultSaga sagaWith(List<ActionCommand> emitted, List<String> statuses, CapPolicy policy) {
        ResultSaga saga = new ResultSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<dev.codespire.contract.event.DomainEvent> handle(
                    String reviewId, dev.codespire.contract.command.RecordCommand command) {
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, 1L, ReviewState.Status.REVIEWING,
                        COMMIT, Set.of(), null, Map.of(), Set.of());
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }

            @Override
            public void clearScheduledRetry(String reviewId) {
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                statuses.add(status);
            }

            @Override
            public void setNote(String reviewId, String note) {
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
                return Optional.empty();
            }
        };
        saga.capPolicy = policy;
        return saga;
    }
}
