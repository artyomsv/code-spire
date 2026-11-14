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
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The fixture for the verdict tests: a ResultSaga wired with hand-written fakes (ADR-047 §4).
 *
 * <p>Collaborators are field-injected, so hand-written fakes are set directly — no CDI container, no
 * mocking framework. Every method the ReviewGenerated path reaches is overridden on purpose: these are
 * plain unit tests with no datasource, and an un-overridden concrete method opens a real connection.
 */
class ResultSagaVerdictFixture {

    static final RepoRef REPO = new RepoRef("acme", "web");
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, 412L);
    static final String COMMIT = "cafe123";
    private static final int FIRST_RUN = 1;

    final List<ActionCommand> emitted = new ArrayList<>();
    final List<String> timelineLines = new ArrayList<>();
    final List<String> recordedVerdicts = new ArrayList<>();
    int openBlockers;
    /** A review whose only open finding is a MAJOR: blocking at MAJOR and MINOR, not at BLOCKER. */
    boolean majorOnly;
    Optional<BlockApprovalAt> level = Optional.of(BlockApprovalAt.BLOCKER);
    Optional<Boolean> setting = Optional.of(true);
    boolean identityKnown = true;

    static ReviewGenerated generated() {
        return new ReviewGenerated(REVIEW_ID, 412L, COMMIT, new ReviewResult(List.of(), "s", ModelUsage.of(null, 0, 0)));
    }


    /** Minimal ResultSaga wired for ReviewGenerated -> PostComments and CommentsPosted. */
    ResultSaga saga() {
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
            public Optional<String> runTokenOf(String reviewId) {
                return Optional.of("TEST-run-7");
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
            public int openBlocking(String reviewId, BlockApprovalAt at) {
                if (majorOnly) {
                    return at == BlockApprovalAt.BLOCKER ? 0 : 1;
                }
                return openBlockers;
            }

            @Override
            public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef, String reviewUrl) {
                recordedVerdicts.add(verdict + ":" + reviewRef + ":" + commit + ":" + reviewUrl);
            }
        };
        saga.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override
            public Optional<Boolean> reviewerMayApprove(UUID id) {
                return setting;
            }

            @Override
            public Optional<BlockApprovalAt> blockApprovalAt(UUID id) {
                return level;
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
