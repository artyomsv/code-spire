package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.memory.PreferenceFilter;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.readmodel.SuppressionBatch;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The saga's collaborators for {@link ResultSagaHarnessReviewTest}, each overriding every method the ContextAssembled,
 * ReviewGenerated and refusal paths reach: an un-overridden concrete method opens a real connection from a unit test
 * (CLAUDE.md, saga test fakes). Those that record take the list they record into.
 */
final class HarnessSagaFakes {

    static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    static final long PR = 7L;
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, PR);
    static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    static final PreferenceFilter NO_PREFERENCES = new PreferenceFilter() {
        @Override
        public Filtered apply(RepoRef repo, ReviewResult result) {
            return new Filtered(result, List.of());
        }
    };

    static final FindingProjection SILENT_FINDINGS = new FindingProjection() {
        @Override
        public void recordGenerated(String reviewId, int round, String commit, List<Finding> findings) {
        }

        @Override
        public void recordThreadRefs(String reviewId, List<CommentsPosted.PostedInline> posted) {
        }

        @Override
        public void recordVerdicts(String reviewId, int round, List<FindingVerdict> verdicts) {
        }

        @Override
        public void markSuppressed(String reviewId, int round, SuppressionBatch batch) {
        }
    };

    private HarnessSagaFakes() {
    }

    /** A review in REVIEWING on {@link #COMMIT}; every record command is kept and appends nothing. */
    static ReviewLifecycleService lifecycle(List<RecordCommand> recorded) {
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of();
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return new ReviewState(reviewId, REPO, PR, ReviewState.Status.REVIEWING, COMMIT, Set.of(), null,
                        Map.of(), Set.of());
            }
        };
    }

    static CommandsEmitter commands(List<ActionCommand> emitted) {
        return new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
    }

    /** Keeps each timeline line's type, which is where a started run's id is named. */
    static TimelineBroadcaster timeline(List<String> types) {
        return new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                types.add(type);
            }
        };
    }

    static ReviewThreadView silentThreads() {
        return new ReviewThreadView() {
            @Override
            public void markFindingThread(String reviewId, ThreadRef thread, String path, int line) {
            }

            @Override
            public void markSummaryThread(String reviewId, ThreadRef thread) {
            }
        };
    }

    /** A repository whose reviewer may approve and is known, reviewed through a usable account. */
    static void wireRepository(ResultSaga saga) {
        saga.repositories = new RepositoryRegistry() {
            @Override
            public Optional<Boolean> reviewerMayApprove(UUID id) {
                return Optional.of(true);
            }
        };
        saga.repositoryAccounts = new RepositoryAccounts() {
            @Override
            public boolean reviewerIdentityKnown(UUID repositoryId) {
                return true;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-packed-scm");
            }
        };
    }

    /** What the API path reaches before GenerateReview: an LLM to spend on, a spend cap that allows, a prompt. */
    static void wireCalls(ResultSaga saga) {
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveForReview(String reviewId) {
                return DefaultLlm.spendable("TEST-packed-llm", "TEST-model");
            }
        };
        saga.spendGate = new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.allow());
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public PromptTemplate forKind(PromptKind kind, RepoRef repo) {
                return null;
            }
        };
        wireCharges(saga);
    }

    /** What a charge reaches: a price and the round it is filed under. */
    private static void wireCharges(ResultSaga saga) {
        saga.llmModels = new LlmModelRegistry() {
            @Override
            public List<ChargeLine> priceCall(String model, ModelUsage usage) {
                return List.of();
            }
        };
        saga.runs = new ReviewRuns() {
            @Override
            public int currentRun(String reviewId) {
                return 1;
            }

            @Override
            public int roundOrUnknown(String reviewId) {
                return 1;
            }
        };
    }
}
