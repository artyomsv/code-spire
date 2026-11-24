package dev.codespire.orchestrator.review.checks;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.lifecycle.ReviewLifecycle;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.StartReview;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The gate, the resolver and the outcomes wired with hand-written fakes: no CDI container, no database, no forge.
 * The aggregate is the real {@link ReviewLifecycle} decider over an in-memory state, so its commit guard is the real one.
 */
abstract class ChecksWaitFixture {

    static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    static final UUID REPOSITORY_ID = TestRepositoryProjection.REPOSITORY_ID;
    static final String HEAD = "a".repeat(40);
    static final String NEXT_HEAD = "b".repeat(40);

    final InMemoryChecksWaits waits = new InMemoryChecksWaits();
    final List<ActionCommand> emitted = new ArrayList<>();
    final List<RecordCommand> recorded = new ArrayList<>();
    final List<String> statuses = new ArrayList<>();
    final List<String> history = new ArrayList<>();
    final List<String> reads = new ArrayList<>();
    ReviewState state = reviewing(HEAD);
    StartReview setting = StartReview.AFTER_CHECKS;
    boolean checksReadable = true;
    ChecksVerdict verdict = ChecksVerdict.PENDING;
    boolean verdictLabels;
    String note;

    static ReviewState reviewing(String head) {
        return new ReviewState(REVIEW_ID, REPO, 7L, ReviewState.Status.REVIEWING, head, Set.of(), null, Map.of(), Set.of());
    }

    static ChecksVerdict green() {
        return new ChecksVerdict(ChecksVerdict.Kind.GREEN, List.of());
    }

    static ChecksVerdict red(String... failed) {
        return new ChecksVerdict(ChecksVerdict.Kind.RED, List.of(failed));
    }

    static ActionCommand.FetchDiff fetch(String head) {
        return new ActionCommand.FetchDiff(REVIEW_ID, REPO, 7L, head, "TEST-packed-at-event", true);
    }

    /** A wait on {@code head} that started {@code ageSeconds} ago. */
    static ChecksWait waitOn(String head, long ageSeconds) {
        return new ChecksWait(REVIEW_ID, REPOSITORY_ID, head, true, Instant.now().minusSeconds(ageSeconds));
    }

    List<ActionCommand.FetchDiff> fetches() {
        return emitted.stream().filter(ActionCommand.FetchDiff.class::isInstance)
                .map(ActionCommand.FetchDiff.class::cast).toList();
    }

    ReviewStartGate gate() {
        ReviewStartGate gate = new ReviewStartGate();
        gate.projection = projection();
        gate.setting = new StartReviewSetting() {
            @Override
            public StartReview of(UUID repositoryId) {
                return setting;
            }
        };
        gate.reader = reader();
        gate.waits = waits;
        gate.commands = commands();
        return gate;
    }

    ChecksWaitResolver resolver() {
        ChecksWaitResolver resolver = new ChecksWaitResolver();
        resolver.waits = waits;
        resolver.reader = reader();
        resolver.outcomes = outcomes();
        resolver.lifecycle = lifecycle();
        resolver.projection = projection();
        return resolver;
    }

    ChecksWaitSweep sweep() {
        ChecksWaitSweep sweep = new ChecksWaitSweep();
        sweep.waits = waits;
        sweep.resolver = resolver();
        return sweep;
    }

    private ChecksWaitOutcomes outcomes() {
        ChecksWaitOutcomes outcomes = new ChecksWaitOutcomes();
        outcomes.waits = waits;
        outcomes.lifecycle = lifecycle();
        outcomes.projection = projection();
        outcomes.commands = commands();
        outcomes.credentials = new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-packed-at-start");
            }
        };
        outcomes.repositories = new RepositoryRegistry() {
            @Override
            public Optional<Boolean> verdictLabels(UUID repositoryId) {
                return Optional.of(verdictLabels);
            }
        };
        return outcomes;
    }

    private ChecksReader reader() {
        return new ChecksReader() {
            @Override
            public boolean available(String reviewId) {
                return checksReadable;
            }

            @Override
            public ChecksVerdict read(ChecksWait wait) {
                reads.add(wait.head());
                return verdict;
            }
        };
    }

    private ReviewLifecycleService lifecycle() {
        ReviewLifecycle decider = new ReviewLifecycle();
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                List<DomainEvent> events = decider.decide(command, state);
                for (DomainEvent event : events) state = decider.evolve(state, event);
                return events;
            }

            @Override
            public ReviewState currentState(String reviewId) {
                return state;
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

    private ReviewProjection projection() {
        return new TestRepositoryProjection() {
            @Override
            public void updateStatus(String reviewId, String status) {
                statuses.add(status);
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                statuses.add(status);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                history.add(type);
            }

            @Override
            public void setNote(String reviewId, String text) {
                note = text;
            }

            @Override
            public boolean archived(String reviewId) {
                return false;
            }
        };
    }
}
