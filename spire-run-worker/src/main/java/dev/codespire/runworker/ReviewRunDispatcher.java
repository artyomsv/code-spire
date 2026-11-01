package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.concurrent.CompletionStage;

/**
 * Consumes {@code cs.review-runs}, the run-worker's review lane (harness reviews, step 4): claim, ack, launch read-only,
 * report — the build lane's order through the same {@link RunExecution}, on its own topic and consumer group, so a
 * review never waits behind an hour-long build.
 *
 * <p>Unordered, on the named pool {@code review-runs}, whose {@code max-concurrency} bounds how many reviews run at once
 * on this instance ({@code SPIRE_RUN_REVIEW_CONCURRENCY}). The execute claim deduplicates a redelivered review exactly
 * as it does a build, and the cancel claim and the registry stop one before or while it runs.
 */
@ApplicationScoped
public class ReviewRunDispatcher {

    private static final Logger LOG = Logger.getLogger(ReviewRunDispatcher.class);

    @Inject
    RunClaimStore claims;

    /** Only to stop a unit cancelled while it was being created, as on the build lane. */
    @Inject
    RunRuntime runtime;

    @Inject
    RunLauncher launcher;

    @Inject
    WorkspaceLeases leases;

    @Inject
    RunRegistry registry;

    @Inject
    RunFailures failures;

    @Inject
    @Channel("run-results-out")
    Emitter<Record<String, RunResult>> results;

    /** The one writer to the transcript channel; the build lane and the control listener share it. */
    @Inject
    RunTranscript transcript;

    @ConfigProperty(name = "spire.run.result-ack-seconds")
    long resultAckSeconds;

    @Incoming("review-runs-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    @Blocking(value = "review-runs", ordered = false)
    public CompletionStage<Void> onCommand(Message<RunCommand> message) {
        RunCommand command = message.getPayload();
        if (command == null) {
            LOG.warn("nacking an unreadable review run command; the failure strategy dead-letters it");
            return message.nack(new IllegalArgumentException("unreadable review run command"));
        }
        MDC.put(RunDispatcher.RUN_ID_MDC, command.runId());
        try {
            return handle(message, command);
        } finally {
            MDC.remove(RunDispatcher.RUN_ID_MDC);
        }
    }

    /**
     * Only a review run is run here. Anything else is acked and launched nowhere, and keeps its execute slot free: a
     * build taken here would hold one of the review pool's threads for an hour, and its own lane would then refuse it as
     * a redelivery.
     */
    private CompletionStage<Void> handle(Message<RunCommand> message, RunCommand command) {
        if (!(command instanceof RunCommand.ExecuteReviewRun review)) {
            LOG.warnf("run %s: a %s arrived on cs.review-runs, where only review runs are run; it was NOT run",
                    command.runId(), command.getClass().getSimpleName());
            RunExecution.ack(message);
            return RunExecution.DONE;
        }
        RunLane lane = new RunLane(claims, runtime, leases, registry, failures, results, transcript, resultAckSeconds);
        return new RunExecution(lane).execute(message, review.execution(), keeper -> launcher.launchReview(review, keeper));
    }
}
