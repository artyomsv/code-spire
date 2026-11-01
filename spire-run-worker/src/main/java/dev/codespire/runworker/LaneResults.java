package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a lane's results with a bounded wait for the broker's acknowledgment, and never throws.
 *
 * <p>A lane acks its record before the run, so nothing after the ack may throw: the record is settled and the claim is
 * taken, and a throw would only dead-letter a command that has already run. A result the broker refuses is logged at
 * ERROR and replaced, once, by a compact {@code RunFailed} that carries no path lists — the shape that gets refused is a
 * huge one — so the orchestrator still learns the run ended.
 */
final class LaneResults {

    private static final Logger LOG = Logger.getLogger(LaneResults.class);

    private final Emitter<Record<String, RunResult>> results;

    private final long ackSeconds;

    LaneResults(Emitter<Record<String, RunResult>> results, long ackSeconds) {
        this.results = results;
        this.ackSeconds = ackSeconds;
    }

    void emit(RunResult result) {
        if (publish(result)) {
            return;
        }
        if (result instanceof RunResult.RunFinished finished && publish(compact(finished))) {
            return;
        }
        LOG.errorf("run %s is complete but unreported: the broker refused its result twice", result.runId());
    }

    /**
     * Its own cause rather than an alias of WORKER_FAILED, which answers "retryable". The broker refused this result
     * twice, the second time compacted, so re-running the agent produces a result it refuses again for the same reason.
     * An operator raises the record limit; nobody re-runs anything.
     */
    private static RunResult compact(RunResult.RunFinished finished) {
        return new RunResult.RunFailed(finished.runId(), "RESULT_UNPUBLISHABLE",
                "the broker refused the run's full result (" + finished.changedPaths().size()
                        + " changed paths, " + finished.blocked().size() + " blocked); the branch"
                        + (finished.pushedRef() == null ? " was not pushed" : " is at " + finished.pushedRef()),
                RunFailureCause.RESULT_UNPUBLISHABLE.isRetryable(),
                // The agent's spend survives the result being too large to publish, and this failure is the ONLY record
                // of it that reaches the orchestrator. The hosts survive too: capped at the arm, they are not the bulk.
                finished.tokenUsage(), finished.egress());
    }

    private boolean publish(RunResult result) {
        try {
            // send() itself can throw synchronously — a channel with no subscriber, or one whose downstream was
            // cancelled at shutdown — and that throw would be the one escaping after the ack. Inside the guard.
            results.send(Record.of(result.runId(), result)).toCompletableFuture().get(ackSeconds, TimeUnit.SECONDS);
            return true;
        } catch (IllegalStateException e) {
            LOG.errorf(e, "the channel refused %s", describe(result));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "interrupted publishing %s", describe(result));
            return false;
        } catch (ExecutionException e) {
            LOG.errorf(e.getCause(), "the broker did not accept %s", describe(result));
            return false;
        } catch (TimeoutException e) {
            LOG.errorf("no broker acknowledgment within %ds for %s", ackSeconds, describe(result));
            return false;
        }
    }

    private static String describe(RunResult result) {
        return result.getClass().getSimpleName() + " for run " + result.runId();
    }
}
