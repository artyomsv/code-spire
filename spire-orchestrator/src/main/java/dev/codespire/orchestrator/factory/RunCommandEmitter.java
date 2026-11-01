package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/**
 * Dispatches a run onto {@code cs.run-commands}, keyed by runId, and waits for the broker's ack.
 *
 * <p>The wait is the point. {@code POST /api/runs} answers 201 only after this returns, so a lost
 * dispatch surfaces as a 5xx rather than as an accepted run that never starts — the gateway makes
 * the same choice before its 202, and for the same reason: a silent non-start is indistinguishable
 * from a run still queued.
 */
@ApplicationScoped
public class RunCommandEmitter {

    @Inject
    @Channel("run-commands-out")
    Emitter<RunCommand> commands;

    /**
     * Control, onto {@code cs.run-control} — a different topic, on purpose.
     *
     * <p>The work topic is read by an ordered, blocking consumer that holds each record for the whole
     * duration of the run it started, so a cancel published there is read only once the run it
     * cancels has already finished. Publishing control on the work topic is therefore not a
     * near-equivalent shortcut; it is the no-op the separate topic exists to remove.
     *
     * <p>Awaits the ack like {@link #dispatch}, so an operator's cancel that never reached the broker
     * is a 5xx rather than a 202 for a run that keeps going.
     */
    @Inject
    @Channel("run-control-out")
    Emitter<RunCommand> control;

    /**
     * Harness review runs, onto {@code cs.review-runs}: the run-worker's review lane (harness reviews, step 4). A
     * different topic for the reason control has one: the build lane's consumer holds each record for its whole run,
     * so a review published there would wait behind an hour-long build.
     */
    @Inject
    @Channel("review-runs-out")
    Emitter<RunCommand> reviews;

    public void dispatch(RunCommand command) {
        KafkaSends.sendAndAwait(commands, command.runId(), command,
                "run command " + command.getClass().getSimpleName() + " for " + command.runId());
    }

    /** A harness review run, onto {@code cs.review-runs}: the run-worker's review lane, never the build lane. */
    public void dispatchReview(RunCommand command) {
        KafkaSends.sendAndAwait(reviews, command.runId(), command,
                "review run " + command.getClass().getSimpleName() + " for " + command.runId());
    }

    public void control(RunCommand command) {
        KafkaSends.sendAndAwait(control, command.runId(), command,
                "run control " + command.getClass().getSimpleName() + " for " + command.runId());
    }
}
