package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observe-mode and author-allowlist gates in {@link IntegrationSaga}.
 * Collaborators are field-injected, so the test (same package) sets simple
 * hand-written fakes directly — no CDI container, no mocking framework.
 */
class IntegrationSagaPolicyTest {

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean reviewRegistered;

    private IntegrationSaga sagaWith(ReviewPolicy policy, boolean runStarts) {
        IntegrationSaga saga = new IntegrationSaga();
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                reviewRegistered = true;
                return runStarts ? List.of(new DomainEvent.ReviewRequested("cafe123", "OPENED")) : List.of();
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
        saga.policy = policy;
        return saga;
    }

    private static PullRequestEventReceived pr(String accountId, String username) {
        return new PullRequestEventReceived(
                new RepoRef("acme", "web"), 412L, PrAction.OPENED,
                "Refactor checkout", "desc", "feature", "main",
                DiffRefs.headOnly("cafe123"),
                Author.of(accountId, username, "Display Name"),
                "https://example/pr/412");
    }

    @Test
    void active_allowlistedAuthor_emitsFetchDiff() {
        var saga = sagaWith(new ReviewPolicy("active", "alice"), true);
        saga.on(pr("acc-1", "alice"));
        assertTrue(reviewRegistered);
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void observeMode_registersButEmitsNoCommands() {
        var saga = sagaWith(new ReviewPolicy("observe", "alice"), true);
        saga.on(pr("acc-1", "alice"));
        assertTrue(reviewRegistered, "review is still registered in observe mode");
        assertTrue(emitted.isEmpty(), "observe mode emits no action commands");
        assertTrue(notes.contains("ReviewObserved"));
    }

    @Test
    void authorNotInAllowlist_skippedEntirely() {
        var saga = sagaWith(new ReviewPolicy("active", "alice"), true);
        saga.on(pr("acc-9", "bob"));
        assertFalse(reviewRegistered, "a non-allowlisted author must not create a review");
        assertTrue(emitted.isEmpty());
        assertTrue(notes.contains("PullRequestSkipped"));
    }

    @Test
    void allowlistMatchesByAccountId() {
        var saga = sagaWith(new ReviewPolicy("active", "712020:d1005216"), true);
        saga.on(pr("712020:d1005216", "any-nickname"));
        assertEquals(1, emitted.size());
    }
}
