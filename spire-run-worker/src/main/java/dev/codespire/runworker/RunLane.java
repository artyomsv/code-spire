package dev.codespire.runworker;

import dev.codespire.contract.event.RunResult;
import dev.codespire.runtime.RunRuntime;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * What one lane needs to execute a run: the build lane's {@link RunDispatcher} and the review lane's
 * {@link ReviewRunDispatcher} (harness reviews, step 4) hand the same collaborators to one {@link RunExecution}, so the
 * two lanes cannot drift on the claim, ack, cancel and lease rules.
 *
 * @param resultAckSeconds how long a result waits for the broker's acknowledgment ({@code spire.run.result-ack-seconds})
 */
record RunLane(RunClaimStore claims, RunRuntime runtime, WorkspaceLeases leases, RunRegistry registry,
               RunFailures failures, Emitter<Record<String, RunResult>> results, RunTranscript transcript,
               long resultAckSeconds) {
}
