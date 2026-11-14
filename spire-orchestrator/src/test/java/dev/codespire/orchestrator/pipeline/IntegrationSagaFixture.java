package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * The shared wiring of the IntegrationSaga tests: hand-written fakes set on the field-injected collaborators, and
 * the lists that record what each one was asked. No CDI container, no mocking framework.
 */
abstract class IntegrationSagaFixture {

    final List<ActionCommand> emitted = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** Each timeline note as {@code lane|detail} — the halves the type alone does not pin. */
    final List<String> noteDetails = new ArrayList<>();
    final List<String> headerProviderTypes = new ArrayList<>();
    final List<String> refreshedProviderTypes = new ArrayList<>();
    final List<String> rerunInvocations = new ArrayList<>();
    final List<RecordCommand> handledCommands = new ArrayList<>();
    final List<String> prStateCalls = new ArrayList<>();
    /** Fork provenance written per event, as {@code reviewId:fromFork}. */
    final List<String> fromForkCalls = new ArrayList<>();
    /** Durable review-history rows, as {@code type:detail} — distinct from the in-memory timeline. */
    final List<String> appendedEvents = new ArrayList<>();
    /** Reviews whose live harness runs a close asked to stop. */
    final List<String> closedReviewRuns = new ArrayList<>();
    boolean reviewRegistered;
    /** The repository's factory account, when a test names one; otherwise every role answers the reviewer. */
    Optional<ScmProvider> factory;
    /** Set by the archived-notice cases; every other case runs against a live review. */
    boolean reviewIsArchived;

    IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider) {
        return sagaWith(policy, provider, List.of(new DomainEvent.ReviewRequested("cafe123", "OPENED")));
    }

    /**
     * {@code lifecycleResult} is what the (faked) decider returns — an empty list
     * models the real idempotency no-op for a re-delivered same-commit event.
     */
    IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider,
                                     List<DomainEvent> lifecycleResult) {
        IntegrationSaga saga = new IntegrationSaga();
        // The real one reads factory_run and sends on the run-control topic; this records which review a close named.
        saga.reviewRunCancellation = new dev.codespire.orchestrator.review.run.ReviewRunCancellation() {
            @Override public void close(String reviewId) { closedReviewRuns.add(reviewId); }
            @Override public void supersede(String reviewId, int supersededRound) {
                throw new AssertionError("a supersede is heard on cs.events, not here");
            }
        };
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                reviewRegistered = true;
                handledCommands.add(command);
                return lifecycleResult;
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
                notes.add(type);
                // Lane and detail too. Recording only the type left them pinned by nothing, so
                // moving the note to another lane and blanking its text passed every case — and for
                // a refusal that is DELIBERATELY silent the note is the operator's only signal, so
                // its text is the feature rather than decoration.
                noteDetails.add(lane + "|" + detail);
            }
        };
        saga.projection = new dev.codespire.orchestrator.TestRepositoryProjection() {
            /** Every event passes the archival gate first; these fixtures are live unless a case says otherwise. */
            @Override
            public boolean archived(String reviewId) {
                return reviewIsArchived;
            }

            /**
             * Registered, so a gated command has a REACHABLE handler behind the gate.
             *
             * <p>Without this the mutation that deletes the gate dies on an NPE instead of an
             * assertion: the real method calls {@code dataSource.getConnection()} on a null field,
             * and {@code catch (SQLException)} cannot see an NPE. That is this project's recorded
             * fake-coverage trap for the eighth time, and it made the {@code /finding} case red for
             * the wrong reason — proving the path was not taken without proving the gate stopped it.
             */
            @Override
            public boolean registered(String reviewId) {
                return true;
            }

            /** Nothing posted, so a refusal has no summary thread to fall back to. */
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                                       String authorId, String sourceBranch, String destBranch, String sha,
                                       String htmlUrl, String providerType, String status, int stage) {
                headerProviderTypes.add(providerType);
            }

            @Override
            public void refreshHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                                     String authorId, String sourceBranch, String destBranch, String sha,
                                     String htmlUrl, String providerType) {
                refreshedProviderTypes.add(providerType);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                appendedEvents.add(type + ":" + detail);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                appendedEvents.add(type + ":" + detail);
            }

            @Override
            public void setNote(String reviewId, String note) {
            }

            @Override
            public void touch(String reviewId) {
            }

            @Override
            public void setPrState(String reviewId, String prState) {
                prStateCalls.add(reviewId + ":" + prState);
            }

            /**
             * Fork provenance, written on every pull-request event.
             *
             * <p>Instance TEN of this project's recorded fake-coverage trap: un-overridden, the real
             * method opens a {@code DataSource} from a plain unit test. It arrived the same way the
             * previous nine did — a new production write on a path these fixtures already exercised,
             * added by someone who did not re-read which methods the path reaches.
             */
            @Override
            public void setFromFork(String reviewId, boolean fromFork) {
                fromForkCalls.add(reviewId + ":" + fromFork);
            }

            /**
             * Reached only once a reply is actually answered, which no case did until the observe
             * gate needed an active-mode twin. Un-overridden it opens a real {@code DataSource} from
             * a plain unit test — the trap this project has now hit nine times, and the ninth was
             * this fixture, in the round that fixed the eighth.
             */
            @Override
            public void setAnswering(String reviewId, boolean answering) {
            }
        };
        // Third un-overridden method on the path a deleted gate would take. Left null it reaches a
        // null DataSource exactly as the projection fake did, so the NPE would simply move.
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return thread;
            }
        };
        saga.repositoryAccounts = new dev.codespire.orchestrator.repository.RepositoryAccounts() {
            @Override
            public Optional<ScmProvider> resolve(java.util.UUID repositoryId, ProviderRole role) {
                return role == ProviderRole.FACTORY && factory != null ? factory : provider;
            }
        };
        // The self-loop guard resolves the review's bot by the review's stored SCM type.
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return provider;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p, String repositoryWorkspace) {
                return "packed-cred:" + repositoryWorkspace;
            }
        };
        // Always answers, so the reply cases test the GATE rather than the conversation policy.
        // A fake that declined would make the observe assertion pass for the wrong reason.
        saga.conversation = new ConversationSaga() {
            @Override
            public Optional<ActionCommand> planFollowUp(AuthorReplied e) {
                return Optional.of(new ActionCommand.AnswerFollowUp(e.reviewId(), e.repo(), e.prId(),
                        e.threadRef(), e.commentId(), e.text(), "scm-cred", "llm-cred", false, 1, 100L, 2.0));
            }
        };
        saga.policy = policy;
        saga.rerunService = new ReviewRerunService() {
            @Override
            public boolean rerun(String workspace, String slug, long pr) {
                rerunInvocations.add(workspace + "/" + slug + "#" + pr);
                return true;
            }
        };
        return saga;
    }

    static Optional<ScmProvider> provider(List<String> authors) {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "CF", "bitbucket-cloud", "https://x",
                "bearer", null, "secret", "acct", true, authors, null, null, ProviderRole.REVIEWER));
    }

    /** A ReviewPolicy fake with a fixed mode — the saga only reads observeOnly(). */
    static ReviewPolicy policyMode(boolean observeOnly) {
        return new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return observeOnly;
            }
        };
    }

    static PullRequestEventReceived pr(String accountId, String username) {
        return pr(accountId, username, null);
    }

    static PullRequestEventReceived pr(String accountId, String username, String providerType) {
        return new PullRequestEventReceived(
                new RepoRef("acme", "web"), 412L, PrAction.OPENED,
                "Refactor checkout", "desc", "feature", "main",
                "cafe123",
                Author.of(accountId, username, "Display Name"),
                "https://example/pr/412", providerType);
    }
}
