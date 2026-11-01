package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Consumes {@code cs.review-runs}, the run-worker's review lane (harness reviews, step 4): claim, ack, launch read-only,
 * report — the build lane's order, on its own topic and its own bounded pool, so a review never waits behind a build.
 */
@ApplicationScoped
public class ReviewRunDispatcher {

    @Inject
    RunClaimStore claims;

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

    @Inject
    RunTranscript transcript;

    public CompletionStage<Void> onCommand(Message<RunCommand> message) {
        return CompletableFuture.completedFuture(null);
    }
}
