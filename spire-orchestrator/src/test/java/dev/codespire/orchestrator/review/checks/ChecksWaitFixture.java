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
import dev.codespire.orchestrator.llm.ReviewRuns;
import dev.codespire.orchestrator.pipeline.CommandsEmitter;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.StartReview;
import dev.codespire.orchestrator.review.run.ReviewRoundLock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The gate, the resolver and the outcomes wired with hand-written fakes: no CDI container, no database, no forge.
 * The aggregate is the real {@link ReviewLifecycle} decider over an in-memory state, so its commit guard is the real
 * one. {@link #duringRead} and {@link #duringSend} run once, inside a read or a send, which is how a test places a
 * second resolver, a push or a crash between one resolver's steps.
 */
abstract class ChecksWaitFixture extends ChecksWaitFakes {

    static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    static final String REVIEW_ID = ReviewIds.reviewId(REPO, 7L);
    static final UUID REPOSITORY_ID = TestRepositoryProjection.REPOSITORY_ID;
    static final String HEAD = "a".repeat(40);
    static final String NEXT_HEAD = "b".repeat(40);

    final InMemoryChecksWaits waits = new InMemoryChecksWaits();
    ReviewState state = reviewing(HEAD);
    int round = 1;
    StartReview setting = StartReview.AFTER_CHECKS;
    boolean checksReadable = true;
    ChecksVerdict verdict = ChecksVerdict.PENDING;
    boolean verdictLabels;
    /** Runs once inside the next read, before it answers. */
    Runnable duringRead;
    /** Runs once inside the next send, before it is acknowledged; it may throw, as a lost broker ack would. */
    Runnable duringSend;

    static ReviewState reviewing(String head) {
        return new ReviewState(REVIEW_ID, REPO, 7L, ReviewState.Status.REVIEWING, head, Set.of(), null, Map.of(), Set.of());
    }

    static ActionCommand.FetchDiff fetch(String head) {
        return new ActionCommand.FetchDiff(REVIEW_ID, REPO, 7L, head, "TEST-packed-at-event", true);
    }

    /** A wait of round {@link #round} on {@code head} that started {@code ageSeconds} ago. */
    ChecksWait waitOn(String head, long ageSeconds) {
        return new ChecksWait(REVIEW_ID, REPOSITORY_ID, head, round, true, Instant.now().minusSeconds(ageSeconds),
                ChecksWait.Stage.WAITING, null);
    }

    /** A new commit or a re-run: the aggregate's next round, as the saga and the re-run service ask for it. */
    void requestRun(String head) {
        round++;
        state = reviewing(head);
        gate().begin(fetch(head));
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
        gate.runs = runs();
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

    ChecksWaitOutcomes outcomes() {
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
        outcomes.rounds = new ReviewRoundLock() {
            @Override
            public <T> T holding(String reviewId, Supplier<T> work) {
                return work.get();
            }
        };
        outcomes.runs = runs();
        return outcomes;
    }

    private ReviewRuns runs() {
        return new ReviewRuns() {
            @Override
            public int roundOrUnknown(String reviewId) {
                return round;
            }
        };
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
                runOnce(() -> duringRead, () -> duringRead = null);
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
                runOnce(() -> duringSend, () -> duringSend = null);
                emitted.add(command);
            }
        };
    }

    private ReviewProjection projection() {
        return recordingProjection();
    }

    /** Runs the hook once, clearing it first, so a hook that re-enters the code under test does not run again. */
    private static void runOnce(Supplier<Runnable> hook, Runnable clear) {
        Runnable once = hook.get();
        if (once == null) return;
        clear.run();
        once.run();
    }
}
