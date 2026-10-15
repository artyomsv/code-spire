package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saga carries the verdict decision on the command, and records what was posted (ADR-047 §4).
 *
 * <p>Collaborators are field-injected, so hand-written fakes are set directly — no CDI container, no
 * mocking framework. Every method the ReviewGenerated path reaches is overridden on purpose: these are
 * plain unit tests with no datasource, and an un-overridden concrete method opens a real connection.
 */
class ResultSagaVerdictTest {

    private static final RepoRef REPO = new RepoRef("acme", "web");
    private static final String REVIEW_ID = ReviewIds.reviewId(REPO, 412L);
    private static final String COMMIT = "cafe123";
    private static final int FIRST_RUN = 1;

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> timelineLines = new ArrayList<>();
    private final List<String> recordedVerdicts = new ArrayList<>();
    private int openBlockers;
    private Optional<Boolean> setting = Optional.of(true);
    private boolean identityKnown = true;

    private static ReviewGenerated generated() {
        return new ReviewGenerated(REVIEW_ID, 412L, COMMIT, new ReviewResult(List.of(), "s", ModelUsage.of(null, 0, 0)));
    }

    @Test
    void theCommandCarriesTheDecision() {
        openBlockers = 1;
        saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.assessment());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, post.verdict());
        assertEquals(Boolean.TRUE, post.reviewerMayApprove());
        assertTrue(timelineLines.contains("ReviewVerdictDecided verdict:REQUEST_CHANGES · assessment:REQUEST_CHANGES · open_blockers=1"),
                timelineLines.toString());
    }

    @Test
    void theSettingOffPostsACommentAndSaysSo() {
        setting = Optional.of(false);
        saga().on(generated());
        var post = assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast());
        assertEquals(ReviewVerdict.APPROVE, post.assessment());
        assertEquals(ReviewVerdict.COMMENT, post.verdict());
        assertEquals(Boolean.FALSE, post.reviewerMayApprove());
    }

    @Test
    void aReviewerWithoutIdentityNeverGetsAnApprove() {
        identityKnown = false;
        saga().on(generated());
        assertEquals(ReviewVerdict.COMMENT, assertInstanceOf(ActionCommand.PostComments.class, emitted.getLast()).verdict());
    }

    @Test
    void commentsPostedWithAVerdictRecordsIt() {
        saga().on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "review:501", List.of(), List.of())
                .withPostedReview(ReviewVerdict.APPROVE, "501"));
        assertEquals(List.of("APPROVE:501:" + COMMIT), recordedVerdicts);
    }

    @Test
    void commentsPostedWithoutAVerdictRecordsNothing() {
        saga().on(new CommentsPosted(REVIEW_ID, 412L, COMMIT, "sum-1", List.of(), List.of()));
        assertTrue(recordedVerdicts.isEmpty());
    }

    /** Minimal ResultSaga wired for ReviewGenerated -> PostComments and CommentsPosted. */
    private ResultSaga saga() {
        ResultSaga saga = new ResultSaga();
        saga.findings = SILENT_FINDINGS;
        saga.preferenceFilter = NO_PREFERENCES;
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, 412L, ReviewState.Status.REVIEWING,
                        COMMIT, java.util.Set.of(), null, java.util.Map.of(), java.util.Set.of());
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.threads = new ReviewThreadView() {
            @Override
            public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
            }

            @Override
            public void markSummaryThread(String reviewId, ThreadRef thread) {
            }
        };
        saga.projection = new dev.codespire.orchestrator.TestRepositoryProjection() {
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                timelineLines.add(type + " " + detail);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
            }

            @Override
            public void updateStage(String reviewId, int stage) {
            }

            @Override
            public void recordOutcome(String reviewId, ReviewResult result, int stage) {
            }

            @Override
            public void setNote(String reviewId, String note) {
            }

            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.of(new PriorRun(COMMIT, "sum-prior-1", List.of()));
            }

            @Override
            public void recordReconciliation(String reviewId,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }

            @Override
            public void recordOpenFindings(String reviewId, ReviewResult result,
                    List<dev.codespire.contract.review.FindingVerdict> verdicts,
                    List<dev.codespire.contract.review.PriorFinding> priorFindings) {
            }

            @Override
            public void touch(String reviewId) {
            }

            @Override
            public void recordCharges(ChargeCall call) {
            }

            @Override
            public void recordPosted(String reviewId, String commit, String summaryCommentId) {
            }

            // The three the decision and the posted-verdict write reach; the real ones open a database.
            @Override
            public int openBlockers(String reviewId) {
                return openBlockers;
            }

            @Override
            public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef) {
                recordedVerdicts.add(verdict + ":" + reviewRef + ":" + commit);
            }
        };
        saga.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override
            public Optional<Boolean> reviewerMayApprove(UUID id) {
                return setting;
            }
        };
        saga.repositoryAccounts = new dev.codespire.orchestrator.repository.RepositoryAccounts() {
            @Override
            public boolean reviewerIdentityKnown(UUID repositoryId) {
                return identityKnown;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("packed-cred");
            }
        };
        saga.llmModels = new LlmModelRegistry() {
            @Override
            public List<ChargeLine> priceCall(String model, ModelUsage usage) {
                return List.of();
            }
        };
        saga.runs = new dev.codespire.orchestrator.llm.ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return FIRST_RUN;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return FIRST_RUN;
            }
        };
        return saga;
    }

    private static final dev.codespire.orchestrator.memory.PreferenceFilter NO_PREFERENCES =
            new dev.codespire.orchestrator.memory.PreferenceFilter() {
                @Override
                public Filtered apply(RepoRef repo, ReviewResult result) {
                    return new Filtered(result, List.of());
                }
            };

    private static final dev.codespire.orchestrator.readmodel.FindingProjection SILENT_FINDINGS =
            new dev.codespire.orchestrator.readmodel.FindingProjection() {
                @Override
                public void recordGenerated(String reviewId, int round, String commit,
                        List<dev.codespire.contract.review.Finding> findings) {
                }

                @Override
                public void recordThreadRefs(String reviewId, List<CommentsPosted.PostedInline> posted) {
                }

                @Override
                public void recordVerdicts(String reviewId, int round,
                        List<dev.codespire.contract.review.FindingVerdict> verdicts) {
                }

                @Override
                public void markSuppressed(String reviewId, int round,
                        dev.codespire.orchestrator.readmodel.SuppressionBatch batch) {
                }
            };
}
