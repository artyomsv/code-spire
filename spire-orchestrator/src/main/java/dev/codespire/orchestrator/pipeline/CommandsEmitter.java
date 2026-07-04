package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;

/** Publishes action commands to cs.commands, keyed by reviewId (CONTRACT §9). */
@ApplicationScoped
public class CommandsEmitter {

    private static final Logger LOG = Logger.getLogger(CommandsEmitter.class);

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    @Channel("commands-out")
    Emitter<ActionCommand> commands;

    public void emit(ActionCommand command) {
        commands.send(Message.of(command,
                Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                        .withKey(command.reviewId()).build()),
                () -> CompletableFuture.<Void>completedFuture(null),
                failure -> {
                    LOG.warnf(failure, "Failed to emit %s", command.getClass().getSimpleName());
                    return CompletableFuture.<Void>completedFuture(null);
                }));
        timeline.record("command", command.getClass().getSimpleName(), command.reviewId(), "");
    }
}
