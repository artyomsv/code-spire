package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observe-mode gate and the per-provider author allowlist in
 * {@link IntegrationSaga}. Collaborators are field-injected, so the test sets
 * hand-written fakes directly — no CDI container, no mocking framework.
 */
class IntegrationSagaPolicyTest {

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> headerProviderTypes = new ArrayList<>();
    private boolean reviewRegistered;

    private IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider) {
        return sagaWith(policy, provider, List.of(new DomainEvent.ReviewRequested("cafe123", "OPENED")));
    }

    /**
     * {@code lifecycleResult} is what the (faked) decider returns — an empty list
     * models the real idempotency no-op for a re-delivered same-commit event.
     */
    private IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider,
                                     List<DomainEvent> lifecycleResult) {
        IntegrationSaga saga = new IntegrationSaga();
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                reviewRegistered = true;
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
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                                       String authorId, String sourceBranch, String destBranch, String sha,
                                       String htmlUrl, String providerType, String status, int stage) {
                headerProviderTypes.add(providerType);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
            }

            @Override
            public void setNote(String reviewId, String note) {
            }
        };
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                return provider;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "packed-cred:" + p.workspace();
            }
        };
        saga.policy = policy;
        return saga;
    }

    private static Optional<ScmProvider> provider(List<String> authors) {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "CF", "bitbucket-cloud", "https://x", "acme",
                "bearer", null, "secret", "acct", true, authors));
    }

    /** A ReviewPolicy fake with a fixed mode — the saga only reads observeOnly(). */
    private static ReviewPolicy policyMode(boolean observeOnly) {
        return new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return observeOnly;
            }
        };
    }

    private static PullRequestEventReceived pr(String accountId, String username) {
        return pr(accountId, username, null);
    }

    private static PullRequestEventReceived pr(String accountId, String username, String providerType) {
        return new PullRequestEventReceived(
                new RepoRef("acme", "web"), 412L, PrAction.OPENED,
                "Refactor checkout", "desc", "feature", "main",
                DiffRefs.headOnly("cafe123"),
                Author.of(accountId, username, "Display Name"),
                "https://example/pr/412", providerType);
    }

    @Test
    void eventWithProviderType_resolvesByTypeNotWorkspaceAlone() {
        // Two SCMs can share a workspace name; when the event names its type the saga
        // MUST resolve by (type, workspace). resolveByWorkspace throwing proves it isn't
        // used as the fallback here.
        IntegrationSaga saga = sagaWith(policyMode(false), provider(List.of()));
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "BB", type, "https://x", workspace,
                        "bearer", null, "secret", "acct", true, List.of()));
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                throw new AssertionError("must resolve by (type, workspace), not workspace alone");
            }
        };

        saga.on(pr("acct", "user", "bitbucket-cloud"));

        assertTrue(headerProviderTypes.contains("bitbucket-cloud"), "resolved by the event's SCM type");
        assertTrue(emitted.stream().anyMatch(ActionCommand.FetchDiff.class::isInstance),
                "an active review dispatched FetchDiff via the type-resolved provider");
    }

    @Test
    void noProviderRegistered_skippedEntirely() {
        var saga = sagaWith(policyMode(false), Optional.empty());
        saga.on(pr("acc-1", "alice"));
        assertFalse(reviewRegistered);
        assertTrue(emitted.isEmpty());
        assertTrue(notes.contains("PullRequestSkipped"));
    }

    @Test
    void authorNotInProviderAllowlist_skipped() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-9", "bob"));
        assertFalse(reviewRegistered);
        assertTrue(emitted.isEmpty());
        assertTrue(notes.contains("PullRequestSkipped"));
    }

    @Test
    void emptyProviderAllowlist_reviewsEveryone() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(pr("acc-1", "anyone"));
        assertTrue(reviewRegistered);
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void active_allowlistedAuthor_emitsFetchDiff() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void allowlistMatchesByAccountId() {
        var saga = sagaWith(policyMode(false), provider(List.of("712020:d1005216")));
        saga.on(pr("712020:d1005216", "any-nickname"));
        assertEquals(1, emitted.size());
    }

    @Test
    void registerHeader_carriesTheResolvedProviderType() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes,
                "the registered provider's type is projected onto the review row (C7)");
    }

    @Test
    void botAuthoredCommand_isDroppedBySelfLoopGuard() {
        // The bot's account id is the provider's botAccountId ("acct") — the guard
        // moved from the gateway to here, resolving it from the registry.
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acct", "spire-bot", "Bot")));
        assertTrue(notes.contains("SelfLoopDropped"), "bot-authored /command is dropped (self-loop guard)");
        assertTrue(emitted.isEmpty());
    }

    @Test
    void humanAuthoredCommand_isNotDroppedBySelfLoopGuard() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("human-1", "alice", "Alice")));
        assertFalse(notes.contains("SelfLoopDropped"), "a human /command is not a self-loop");
    }

    @Test
    void redeliveredSameCommit_registersHeaderButEmitsNoWork() {
        // A GitHub re-delivery (or a duplicate `synchronize`) for the same head SHA
        // reaches the saga again. The decider no-ops it (proven in ReviewLifecycleTest:
        // sameCommitIsIdempotentNoOp) — modeled here by an empty lifecycle result — so
        // the saga registers no new work: no second FetchDiff, no second review run.
        var saga = sagaWith(policyMode(false), provider(List.of("alice")), List.of());
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes,
                "the header upsert is idempotent — the row is refreshed, not duplicated");
        assertTrue(emitted.isEmpty(), "a re-delivered same-commit event dispatches no FetchDiff");
    }

    @Test
    void observeMode_registersHeaderButDoesNotStartTheReview() {
        var saga = sagaWith(policyMode(true), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        // Observe registers the dashboard header but MUST NOT advance the aggregate —
        // otherwise a later active registration of the same commit stays stuck in DIFF.
        assertFalse(reviewRegistered, "observe mode emits no ReviewRequested (aggregate untouched)");
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes, "the dashboard header is still registered");
        assertTrue(emitted.isEmpty(), "observe mode emits no action commands");
        assertTrue(notes.contains("ReviewObserved"));
    }
}
