package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A real {@link RunLaunch} whose review send may have reached the broker and whose record of that then fails: the
 * emitter keeps the command and throws an ack failure that may have landed, and the row's {@code dispatchUncertain}
 * write throws as the real one does on a failed statement (review of PR #368, code-quality/M-2).
 *
 * <p>Public for {@code HarnessDispatchFailureTest} in the pipeline package; the launch's fields are package-private.
 * Every emitter and row method off this path throws, because an un-overridden one reaches a real channel or database
 * (CLAUDE.md, saga test fakes).
 */
public final class AmbiguousReviewLaunch {

    private final List<RunCommand> sent = new ArrayList<>();
    private final List<String> uncertainWritesTried = new ArrayList<>();

    /** The commands the emitter was handed, which the broker may hold. */
    public List<RunCommand> sent() {
        return sent;
    }

    /** The run ids whose uncertain status the launch tried to write. */
    public List<String> uncertainWritesTried() {
        return uncertainWritesTried;
    }

    public RunLaunch launch() {
        RunLaunch launch = new RunLaunch();
        launch.emitter = emitter();
        launch.projection = rows();
        return launch;
    }

    private RunCommandEmitter emitter() {
        return new RunCommandEmitter() {
            @Override
            public void dispatchReview(RunCommand command) {
                sent.add(command);
                throw BrokerAckFailure.notAcknowledged("TEST review run " + command.runId() + " was not acknowledged",
                        new IllegalStateException("TEST-no-ack-within-the-budget"));
            }

            @Override
            public void dispatch(RunCommand command) {
                throw new AssertionError("a review never rides the build lane");
            }

            @Override
            public void control(RunCommand command) {
                throw new AssertionError("a dispatch must not travel on the control topic");
            }
        };
    }

    private FactoryRunProjection rows() {
        return new FactoryRunProjection() {
            /** The real update's shape on a failed statement: an IllegalStateException around the SQLException. */
            @Override
            public void dispatchUncertain(String runId, String detail) {
                uncertainWritesTried.add(runId);
                throw new IllegalStateException("Failed to update run " + runId,
                        new SQLException("TEST-transient-connection-failure"));
            }

            @Override
            public void dispatchFailed(String runId, String detail) {
                throw new AssertionError("an ack that may have landed is never a definite miss");
            }

            @Override
            public boolean queued(QueuedRun row, String taskSummary, UUID repositoryId) {
                throw new AssertionError("the row is written by the caller, before the launch");
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("unexpected live broadcast");
            }
        };
    }
}
