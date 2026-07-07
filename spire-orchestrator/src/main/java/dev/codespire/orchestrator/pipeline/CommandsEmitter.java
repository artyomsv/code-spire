package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

/** Publishes action commands to cs.commands, keyed by reviewId (CONTRACT §9). */
@ApplicationScoped
public class CommandsEmitter {

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("commands-out")
    Emitter<ActionCommand> commands;

    /**
     * Awaits the broker ack: a failed publish throws, so the calling saga's
     * incoming message fails and lands on cs.dlq instead of the command being
     * silently lost (which would stall the review).
     */
    public void emit(ActionCommand command) {
        KafkaSends.sendAndAwait(commands, command.reviewId(), command,
                command.getClass().getSimpleName() + " for " + command.reviewId());
        timeline.record("command", command.getClass().getSimpleName(), command.reviewId(), "");
    }
}
